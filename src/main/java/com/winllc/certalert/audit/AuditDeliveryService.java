package com.winllc.certalert.audit;

import com.winllc.certalert.repository.AuditDeliveryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers the audit trail to Splunk HEC and syslog, in the background, from the store.
 *
 * <p>Each output has a cursor in {@code audit_delivery}: the last seq it delivered. A worker
 * per output reads the next batch after its cursor, sends it, and moves the cursor - so
 * delivery is at-least-once (a crash between sending and moving resends that batch, which
 * Splunk can de-duplicate on the event id), survives restarts, and never blocks or slows a
 * request: requests only write the store. A failing output retries with exponential backoff
 * up to five minutes; one that has failed for fifteen records {@code audit.delivery-failed},
 * once, and {@code audit.delivery-recovered} when it delivers again.
 */
@Service
public class AuditDeliveryService implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AuditDeliveryService.class);

    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final EnsAuditProperties properties;
    private final AuditStore store;
    private final AuditDeliveryRepository cursors;
    private final AuditLog audit;
    private final AuditSinks sinks;
    private final TransactionTemplate transaction;
    private final Clock clock;

    private final Map<String, AuditForwarder> forwarders = new LinkedHashMap<>();
    private final Map<String, Thread> workers = new LinkedHashMap<>();
    private final Object signal = new Object();
    private volatile boolean running;

    public AuditDeliveryService(
            EnsAuditProperties properties,
            AuditStore store,
            AuditDeliveryRepository cursors,
            AuditLog audit,
            AuditSinks sinks,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.properties = properties;
        this.store = store;
        this.cursors = cursors;
        this.audit = audit;
        this.sinks = sinks;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        configure();
    }

    private void configure() {
        String host = audit.origin().host();
        String product = audit.origin().product();
        EnsAuditProperties.Splunk splunk = properties.getSplunk();
        if (splunk.isEnabled()) {
            try {
                register(new SplunkHecForwarder(splunk, host, product));
                if (splunk.isInsecure()) {
                    log.warn("ENS_AUDIT_SPLUNK_INSECURE is set: the Splunk HEC certificate is NOT verified. "
                            + "Use this only in a test setup.");
                }
            } catch (RuntimeException e) {
                log.error("Audit output splunk is misconfigured and stays off: {}", e.getMessage());
            }
        }
        log.info("Audit output splunk: {}", forwarders.containsKey("splunk")
                ? "enabled, " + forwarders.get("splunk").describe() : "disabled");
        EnsAuditProperties.Syslog syslog = properties.getSyslog();
        if (syslog.isEnabled()) {
            try {
                register(new SyslogForwarder(syslog, host, product));
            } catch (RuntimeException e) {
                log.error("Audit output syslog is misconfigured and stays off: {}", e.getMessage());
            }
        }
        log.info("Audit output syslog: {}", forwarders.containsKey("syslog")
                ? "enabled, " + forwarders.get("syslog").describe() : "disabled");
    }

    /** Adds an output. Used at startup, and by tests for an output pointed at a stub. */
    public synchronized void register(AuditForwarder forwarder) {
        AuditForwarder previous = forwarders.put(forwarder.name(), forwarder);
        if (previous != null) {
            previous.close();
        }
        if (running && !workers.containsKey(forwarder.name())) {
            startWorker(forwarder.name());
        }
    }

    /** Removes an output: tests only. Its cursor stays. */
    public synchronized void unregister(String name) {
        AuditForwarder previous = forwarders.remove(name);
        if (previous != null) {
            previous.close();
        }
    }

    /** The enabled forwarding outputs, by name. */
    public synchronized List<String> outputs() {
        return List.copyOf(forwarders.keySet());
    }

    /** Lowest cursor among the enabled forwarding outputs: everything up to it has gone everywhere. */
    public long deliveredEverywhere(long head) {
        long lowest = head;
        for (String name : outputs()) {
            lowest = Math.min(lowest, cursors.findById(name).map(AuditDelivery::getLastSeq).orElse(0L));
        }
        return lowest;
    }

    /** New events are in the store: workers waiting out their poll interval look now. */
    void wake() {
        synchronized (signal) {
            signal.notifyAll();
        }
    }

    /**
     * Delivers the next batch for one output.
     *
     * @return how many events were delivered (0 when there was nothing to send)
     * @throws Exception when sending failed - recorded on the cursor first
     */
    public int deliverOnce(String name) throws Exception {
        AuditForwarder forwarder;
        synchronized (this) {
            forwarder = forwarders.get(name);
        }
        if (forwarder == null) {
            return 0;
        }
        AuditDelivery cursor = cursor(name);
        List<StoredEvent> batch = store.after(cursor.getLastSeq(), forwarder.batchSize());
        if (batch.isEmpty()) {
            if (cursor.getFailingSince() != null) {
                succeeded(name, cursor.getLastSeq());
            }
            return 0;
        }
        try {
            forwarder.send(batch);
        } catch (Exception e) {
            failed(name, e);
            throw e;
        }
        succeeded(name, batch.getLast().seq());
        return batch.size();
    }

    private AuditDelivery cursor(String name) {
        return transaction.execute(status -> cursors.findById(name).orElseGet(() -> cursors.save(new AuditDelivery(name, 0))));
    }

    private void succeeded(String name, long seq) {
        boolean recovered = Boolean.TRUE.equals(transaction.execute(status -> {
            AuditDelivery cursor = cursors.findById(name).orElseGet(() -> cursors.save(new AuditDelivery(name, 0)));
            boolean wasAlerted = cursor.isAlerted();
            cursor.delivered(seq, Instant.now(clock));
            cursor.setAlerted(false);
            return wasAlerted;
        }));
        if (recovered) {
            log.info("Audit output {} is delivering again", name);
            audit.recordQuietly(AuditRecord.of(AuditRecord.Category.AUDIT, "audit.delivery-recovered")
                    .actor(AuditRecord.Actor.system("audit delivery"))
                    .severity(AuditRecord.Severity.NOTICE)
                    .detail("output", name));
        }
    }

    private void failed(String name, Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        Instant now = Instant.now(clock);
        Optional<String> alert = Optional.ofNullable(transaction.execute(status -> {
            AuditDelivery cursor = cursors.findById(name).orElseGet(() -> cursors.save(new AuditDelivery(name, 0)));
            cursor.failed(message, now);
            if (!cursor.isAlerted()
                    && Duration.between(cursor.getFailingSince(), now).compareTo(properties.getFailureAlertAfter()) >= 0) {
                cursor.setAlerted(true);
                return message;
            }
            return null;
        }));
        alert.ifPresent(reason -> {
            log.error("Audit output {} has been failing for over {}: {}", name, properties.getFailureAlertAfter(), reason);
            audit.recordQuietly(AuditRecord.of(AuditRecord.Category.AUDIT, "audit.delivery-failed")
                    .actor(AuditRecord.Actor.system("audit delivery"))
                    .outcome(AuditRecord.Outcome.FAILURE)
                    .severity(AuditRecord.Severity.ALERT)
                    .reason(reason)
                    .detail("output", name)
                    .detail("error", reason));
        });
    }

    /** One row of the delivery panel. */
    public record Status(
            String output,
            boolean enabled,
            long lastSeq,
            Instant deliveredAt,
            long backlog,
            String lastError,
            Instant lastErrorAt,
            boolean failing) {}

    public List<Status> statuses() {
        long head = store.headSeq();
        List<Status> rows = new ArrayList<>();
        for (AuditSinks.OutputStatus sink : sinks.statuses()) {
            rows.add(new Status(sink.name(), sink.enabled(), sink.lastSeq(), sink.deliveredAt(),
                    sink.enabled() ? Math.max(0, head - sink.lastSeq()) : 0, sink.lastError(), sink.lastErrorAt(),
                    false));
        }
        List<String> enabled = outputs();
        for (String name : List.of("splunk", "syslog")) {
            Optional<AuditDelivery> cursor = cursors.findById(name);
            boolean on = enabled.contains(name);
            rows.add(new Status(
                    name,
                    on,
                    cursor.map(AuditDelivery::getLastSeq).orElse(0L),
                    cursor.map(AuditDelivery::getDeliveredAt).orElse(null),
                    on ? Math.max(0, head - cursor.map(AuditDelivery::getLastSeq).orElse(0L)) : 0,
                    cursor.map(AuditDelivery::getLastError).orElse(null),
                    cursor.map(AuditDelivery::getLastErrorAt).orElse(null),
                    cursor.map(c -> c.getFailingSince() != null).orElse(false)));
        }
        return rows;
    }

    // --- background ----------------------------------------------------------------------

    @Override
    public synchronized void start() {
        if (running || !properties.isBackground()) {
            return;
        }
        running = true;
        forwarders.keySet().forEach(this::startWorker);
    }

    private void startWorker(String name) {
        Thread worker = new Thread(() -> loop(name), "audit-delivery-" + name);
        worker.setDaemon(true);
        workers.put(name, worker);
        worker.start();
    }

    private void loop(String name) {
        Duration backoff = Duration.ZERO;
        while (running) {
            try {
                int delivered = deliverOnce(name);
                backoff = Duration.ZERO;
                if (delivered == 0) {
                    pause(properties.getPollInterval());
                }
            } catch (Exception e) {
                backoff = backoff.isZero() ? Duration.ofSeconds(1) : backoff.multipliedBy(2);
                if (backoff.compareTo(MAX_BACKOFF) > 0) {
                    backoff = MAX_BACKOFF;
                }
                log.warn("Audit output {} failed ({}); retrying in {}s", name, e.getMessage(), backoff.toSeconds());
                pause(backoff);
            }
        }
    }

    private void pause(Duration duration) {
        synchronized (signal) {
            try {
                signal.wait(Math.max(1, duration.toMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        workers.values().forEach(Thread::interrupt);
        workers.clear();
        forwarders.values().forEach(AuditForwarder::close);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 2000;
    }
}

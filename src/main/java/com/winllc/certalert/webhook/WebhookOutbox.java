package com.winllc.certalert.webhook;

import com.winllc.certalert.LogSafe;
import com.winllc.certalert.cluster.JobLocks;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Posts what {@link WebhookPublisher} queued, the way {@code MailOutbox} sends mail: after
 * the commit, outside any transaction, claimed in the database so one replica posts each
 * delivery once, under a lock in the database so one replica's pass runs at a time, retried
 * further apart each time ({@code retry-initial} doubling to {@code retry-max}) until it is
 * {@code give-up-after} old.
 *
 * <p>Each attempt checks the address again ({@link WebhookAddressGuard}) and posts to the
 * address it checked ({@link WebhookTransport}), signed afresh with the time of the attempt.
 * A 2xx is delivered; anything else - a refusal, a redirect, a 5xx, a timeout - is tried
 * again.
 *
 * <p>A pass is bounded, so one dead receiver cannot hold the lock, and everybody else's
 * deliveries, for hours: no try starts after {@code run-budget}; an endpoint gets at most
 * {@code endpoint-tries-per-run} tries and {@code endpoint-budget} of the pass; and a try the
 * endpoint does not answer at all (no connection, no TLS, no answer in time) puts its other
 * waiting deliveries back to the same retry time, and the endpoint is left alone for the rest
 * of the pass. What is not tried stays waiting, unclaimed, for the next pass.
 *
 * <p>A replica started without {@code cert-alert.webhooks.secret-key} leaves the deliveries
 * of endpoints whose URL or secret is encrypted alone - not claimed, no attempt counted - for
 * a replica that has the key, and says so once a pass.
 */
@Service
@EnableScheduling
public class WebhookOutbox {

    private static final Logger log = LoggerFactory.getLogger(WebhookOutbox.class);

    static final String LOCK = "webhook-outbox";

    static final Duration CLAIM_LEASE = Duration.ofMinutes(5);

    private static final int BATCH = 50;

    public static final String SIGNATURE_HEADER = "X-CertAlert-Signature";

    private final WebhookDeliveryRepository deliveries;
    private final WebhookEndpointRepository endpoints;
    private final WebhookAddressGuard guard;
    private final WebhookTransport transport;
    private final WebhookSecrets secrets;
    private final WebhookProperties properties;
    private final JobLocks locks;
    private final Clock clock;
    private final TransactionTemplate independent;
    private final ReentrantLock sending = new ReentrantLock();
    private final AtomicBoolean nudged = new AtomicBoolean();
    private final ExecutorService background = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "webhook-outbox");
        thread.setDaemon(true);
        return thread;
    });

    public WebhookOutbox(
            WebhookDeliveryRepository deliveries,
            WebhookEndpointRepository endpoints,
            WebhookAddressGuard guard,
            WebhookTransport transport,
            WebhookSecrets secrets,
            WebhookProperties properties,
            JobLocks locks,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.deliveries = deliveries;
        this.endpoints = endpoints;
        this.guard = guard;
        this.transport = transport;
        this.secrets = secrets;
        this.properties = properties;
        this.locks = locks;
        this.clock = clock;
        this.independent = new TransactionTemplate(transactions);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Posts whatever is due. Run on a schedule for the retries, and straight after a commit that queued something. */
    public int deliverDue() {
        if (!sending.tryLock()) {
            return 0;
        }
        try {
            return locks.runExclusively(LOCK, () -> {
                independent.executeWithoutResult(status -> deliveries.releaseLapsed(
                        WebhookDelivery.State.SENDING, WebhookDelivery.State.PENDING, Instant.now(clock)));
                boolean keyed = secrets.encrypts();
                if (!keyed) {
                    List<Long> encrypted = deliveries.findEncryptedWithDue(WebhookDelivery.State.PENDING, Instant.now(clock));
                    if (!encrypted.isEmpty()) {
                        log.warn("Webhook(s) {} are encrypted but this node has no cert-alert.webhooks.secret-key; "
                                + "their deliveries are left for a replica that has it", encrypted);
                    }
                }
                Pass pass = new Pass(properties);
                int sent = 0;
                pages:
                while (true) {
                    List<Object[]> due = deliveries.findDue(WebhookDelivery.State.PENDING, Instant.now(clock), keyed,
                            pass.excluded(), PageRequest.of(0, BATCH));
                    int tried = 0;
                    for (Object[] row : due) {
                        Long id = (Long) row[0];
                        Long endpointId = (Long) row[1];
                        if (pass.overBudget()) {
                            log.info("Webhook pass stopped at its run-budget ({}); what is left waits for the next pass",
                                    properties.getRunBudget());
                            break pages;
                        }
                        if (pass.excludes(endpointId)) {
                            continue;
                        }
                        long started = System.nanoTime();
                        Outcome outcome = attempt(id, keyed);
                        pass.spent(endpointId, outcome, System.nanoTime() - started);
                        if (outcome != Outcome.SKIPPED) {
                            tried++;
                        }
                        if (outcome == Outcome.SENT) {
                            sent++;
                        }
                    }
                    if (due.size() < BATCH || tried == 0) {
                        break;
                    }
                }
                independent.executeWithoutResult(status -> deliveries.deleteFinishedBefore(
                        List.of(WebhookDelivery.State.DELIVERED, WebhookDelivery.State.FAILED),
                        Instant.now(clock).minus(properties.getKeep())));
                return sent;
            }).orElse(0);
        } finally {
            sending.unlock();
        }
    }

    @Scheduled(
            fixedDelayString = "${cert-alert.webhooks.poll-interval:PT30S}",
            initialDelayString = "${cert-alert.webhooks.poll-interval:PT30S}")
    public void poll() {
        if (!properties.isBackground()) {
            return;
        }
        try {
            deliverDue();
        } catch (RuntimeException e) {
            log.error("Posting queued webhooks failed; they are tried again on the next pass", e);
        }
    }

    /** What is waiting, and what was given up on, for the integrations page. */
    public long pending() {
        return deliveries.countByState(WebhookDelivery.State.PENDING);
    }

    public long failed() {
        return deliveries.countByState(WebhookDelivery.State.FAILED);
    }

    @PreDestroy
    void stop() {
        background.shutdownNow();
    }

    /** Once the current transaction commits - or now, where there is none - post what is due. */
    void sendAfterCommit() {
        if (!properties.isBackground()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    nudge();
                }
            });
        } else {
            nudge();
        }
    }

    private void nudge() {
        if (nudged.compareAndSet(false, true)) {
            try {
                background.execute(() -> {
                    nudged.set(false);
                    try {
                        deliverDue();
                    } catch (RuntimeException e) {
                        log.error("Posting queued webhooks failed; they are tried again on the next pass", e);
                    }
                });
            } catch (RuntimeException e) {
                nudged.set(false);
            }
        }
    }

    /** SENT; ANSWERED, but not with a 2xx (or failed before a post); UNANSWERED; SKIPPED, never claimed. */
    private enum Outcome { SENT, NOT_SENT, UNANSWERED, SKIPPED }

    /** What one pass has spent, in all and on each endpoint. */
    private static final class Pass {

        private final long startedAt = System.nanoTime();
        private final long budget;
        private final long endpointBudget;
        private final int endpointTries;
        private final Map<Long, long[]> spent = new java.util.HashMap<>();
        private final java.util.Set<Long> excluded = new java.util.HashSet<>(List.of(-1L));

        Pass(WebhookProperties properties) {
            this.budget = properties.getRunBudget().toNanos();
            this.endpointBudget = properties.getEndpointBudget().toNanos();
            this.endpointTries = properties.getEndpointTriesPerRun();
        }

        boolean overBudget() {
            return System.nanoTime() - startedAt >= budget;
        }

        boolean excludes(Long endpointId) {
            return excluded.contains(endpointId);
        }

        java.util.Collection<Long> excluded() {
            return List.copyOf(excluded);
        }

        void spent(Long endpointId, Outcome outcome, long nanos) {
            long[] used = spent.computeIfAbsent(endpointId, id -> new long[2]);
            used[0]++;
            used[1] += nanos;
            if (outcome == Outcome.UNANSWERED || outcome == Outcome.SKIPPED
                    || used[0] >= endpointTries || used[1] >= endpointBudget) {
                excluded.add(endpointId);
            }
        }
    }

    /**
     * One delivery, if it is still waiting: posted, and what became of it recorded. Without
     * the key, one to an endpoint that was encrypted since it was listed is left unclaimed.
     */
    private Outcome attempt(Long id, boolean keyed) {
        if (!keyed && Boolean.TRUE.equals(independent.execute(status -> deliveries.findById(id)
                .flatMap(row -> endpoints.findById(row.getEndpointId()))
                .map(endpoint -> endpoint.isUrlEncrypted() || endpoint.isSecretEncrypted())
                .orElse(false)))) {
            return Outcome.SKIPPED;
        }
        return attempt(id);
    }

    /** One delivery, if it is still waiting: posted, and what became of it recorded. */
    private Outcome attempt(Long id) {
        Instant now = Instant.now(clock);
        Integer claimed = independent.execute(status -> deliveries.claim(
                id, WebhookDelivery.State.PENDING, WebhookDelivery.State.SENDING, now.plus(CLAIM_LEASE)));
        if (claimed == null || claimed == 0) {
            return Outcome.NOT_SENT;
        }
        WebhookDelivery delivery = deliveries.findById(id).orElse(null);
        WebhookEndpoint endpoint = delivery == null ? null : endpoints.findById(delivery.getEndpointId()).orElse(null);
        if (delivery == null) {
            return Outcome.NOT_SENT;
        }
        if (endpoint == null) {
            record(id, now, null, "The webhook was deleted", true);
            return Outcome.NOT_SENT;
        }
        Integer status = null;
        String error;
        try {
            URI uri = WebhookAddressGuard.parse(secrets.revealUrl(endpoint));
            InetAddress address = guard.resolve(uri);
            byte[] body = delivery.getPayload().getBytes(StandardCharsets.UTF_8);
            long timestamp = Instant.now(clock).getEpochSecond();
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Content-Type", "application/json");
            headers.put("User-Agent", "CertAlert-Webhook/1");
            headers.put("X-CertAlert-Event", delivery.getEventType());
            headers.put("X-CertAlert-Delivery", delivery.getEventId());
            headers.put("X-CertAlert-Timestamp", String.valueOf(timestamp));
            headers.put(SIGNATURE_HEADER, WebhookSecrets.signature(
                    secrets.reveal(endpoint), timestamp, delivery.getPayload()));
            WebhookTransport.Response response = transport.post(uri, address, headers, body);
            status = response.status();
            if (response.successful()) {
                independent.executeWithoutResult(tx -> deliveries.findById(id).ifPresent(row -> row.delivered(response.status(), now)));
                return Outcome.SENT;
            }
            error = "The receiver answered " + response.status();
        } catch (WebhookAddressGuard.RefusedDestinationException e) {
            return unanswered(id, delivery.getEndpointId(), now, "Refused: " + e.getMessage());
        } catch (IOException e) {
            // No connection, no TLS, no answer in time, or the watchdog closed it.
            return unanswered(id, delivery.getEndpointId(), now, describe(e));
        } catch (RuntimeException e) {
            error = describe(e);
        }
        record(id, now, status, error, false);
        return Outcome.NOT_SENT;
    }

    /**
     * A try the endpoint did not answer: recorded, and the endpoint's other waiting deliveries
     * put back to the same time, in one update, so a dead receiver costs one try a backoff step.
     */
    private Outcome unanswered(Long id, Long endpointId, Instant now, String error) {
        Instant retried = record(id, now, null, error, false);
        Instant retryAt = retried != null ? retried : now.plus(properties.getRetryInitial());
        Integer postponed = independent.execute(tx ->
                deliveries.postponeEndpoint(endpointId, WebhookDelivery.State.PENDING, retryAt));
        if (postponed != null && postponed > 0) {
            log.warn("Webhook endpoint {} did not answer; its {} other waiting deliveries wait until {} too",
                    endpointId, postponed, retryAt);
        }
        return Outcome.UNANSWERED;
    }

    /** When the delivery will be tried again, or null where it was given up on. */
    private Instant record(Long id, Instant now, Integer status, String error, boolean giveUp) {
        return independent.execute(tx -> deliveries.findById(id).map(row -> {
            if (giveUp || !now.isBefore(row.getCreatedAt().plus(properties.getGiveUpAfter()))) {
                row.gaveUp(status, error);
                log.error("Gave up on webhook delivery {} ({}) to endpoint {} after {} attempt(s): {}",
                        row.getId(), row.getEventType(), row.getEndpointId(), row.getAttempts(), oneLine(error));
                return (Instant) null;
            }
            Instant retryAt = now.plus(backoff(row.getAttempts() + 1, properties.getRetryInitial(), properties.getRetryMax()));
            row.failed(status, error, retryAt);
            log.warn("Webhook delivery {} ({}) to endpoint {} failed (attempt {}); trying again at {}: {}",
                    row.getId(), row.getEventType(), row.getEndpointId(), row.getAttempts(), retryAt, oneLine(error));
            return retryAt;
        }).orElse(null));
    }

    /** {@code initial} after the first failure, doubling, never more than {@code max}. */
    static Duration backoff(int failures, Duration initial, Duration max) {
        Duration wait = initial;
        for (int i = 1; i < failures && wait.compareTo(max) < 0; i++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(max) > 0 ? max : wait;
    }

    /** A receiver's or a resolver's words, on one line: nothing from outside starts a log line of its own. */
    private static String oneLine(String value) {
        return LogSafe.oneLine(value);
    }

    private static String describe(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return (message == null || message.isBlank() ? cause.getClass().getSimpleName() : message)
                .lines().findFirst().orElse(cause.getClass().getSimpleName());
    }
}

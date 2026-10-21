package com.winllc.certalert.webhook;

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
                int sent = 0;
                while (true) {
                    List<Long> due = deliveries.findDue(
                            WebhookDelivery.State.PENDING, Instant.now(clock), PageRequest.of(0, BATCH));
                    for (Long id : due) {
                        if (attempt(id)) {
                            sent++;
                        }
                    }
                    if (due.size() < BATCH) {
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

    /** One delivery, if it is still waiting: posted, and what became of it recorded. */
    private boolean attempt(Long id) {
        Instant now = Instant.now(clock);
        Integer claimed = independent.execute(status -> deliveries.claim(
                id, WebhookDelivery.State.PENDING, WebhookDelivery.State.SENDING, now.plus(CLAIM_LEASE)));
        if (claimed == null || claimed == 0) {
            return false;
        }
        WebhookDelivery delivery = deliveries.findById(id).orElse(null);
        WebhookEndpoint endpoint = delivery == null ? null : endpoints.findById(delivery.getEndpointId()).orElse(null);
        if (delivery == null) {
            return false;
        }
        if (endpoint == null) {
            record(id, now, null, "The webhook was deleted", true);
            return false;
        }
        Integer status = null;
        String error;
        try {
            URI uri = WebhookAddressGuard.parse(endpoint.getUrl());
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
                return true;
            }
            error = "The receiver answered " + response.status();
        } catch (WebhookAddressGuard.RefusedDestinationException e) {
            error = "Refused: " + e.getMessage();
        } catch (IOException | RuntimeException e) {
            error = describe(e);
        }
        record(id, now, status, error, false);
        return false;
    }

    private void record(Long id, Instant now, Integer status, String error, boolean giveUp) {
        independent.executeWithoutResult(tx -> deliveries.findById(id).ifPresent(row -> {
            if (giveUp || !now.isBefore(row.getCreatedAt().plus(properties.getGiveUpAfter()))) {
                row.gaveUp(status, error);
                log.error("Gave up on webhook delivery {} ({}) to endpoint {} after {} attempt(s): {}",
                        row.getId(), row.getEventType(), row.getEndpointId(), row.getAttempts(), oneLine(error));
                return;
            }
            Instant retryAt = now.plus(backoff(row.getAttempts() + 1, properties.getRetryInitial(), properties.getRetryMax()));
            row.failed(status, error, retryAt);
            log.warn("Webhook delivery {} ({}) to endpoint {} failed (attempt {}); trying again at {}: {}",
                    row.getId(), row.getEventType(), row.getEndpointId(), row.getAttempts(), retryAt, oneLine(error));
        }));
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
        return value == null ? null : value.replaceAll("[\\r\\n\\t]", " ");
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

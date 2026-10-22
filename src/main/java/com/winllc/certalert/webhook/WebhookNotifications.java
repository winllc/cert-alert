package com.winllc.certalert.webhook;

import com.winllc.certalert.alert.AlertNotifier;
import com.winllc.certalert.alert.CertificateAlert;
import com.winllc.certalert.audit.JobAudit;
import com.winllc.certalert.domain.CertificateStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * What the webhooks are told about: every expiry alert - as an alert channel alongside email
 * and the in-app notices - and the end of every job run.
 */
@Component
public class WebhookNotifications implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifications.class);

    private final WebhookPublisher publisher;
    private final WebhookEndpointRepository endpoints;
    private final WebhookProperties properties;
    private final Clock clock;

    /** Whether any endpoint is enabled, looked at no more than once in this long. */
    private volatile Instant activeCheckedAt = Instant.EPOCH;
    private volatile boolean active;

    public WebhookNotifications(
            WebhookPublisher publisher, WebhookEndpointRepository endpoints, WebhookProperties properties, Clock clock) {
        this.publisher = publisher;
        this.endpoints = endpoints;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void send(CertificateAlert alert) {
        WebhookEvent type = alert.status() == CertificateStatus.EXPIRED
                ? WebhookEvent.CERTIFICATE_EXPIRED
                : WebhookEvent.CERTIFICATE_EXPIRING;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ownerType", alert.ownerType().name());
        data.put("ownerId", alert.ownerId());
        data.put("ownerName", alert.ownerName());
        data.put("ownerDn", alert.ownerDn());
        data.put("contact", alert.contact());
        data.put("status", alert.status().name());
        data.put("severity", alert.severity().name());
        data.put("subjectDn", alert.certificateSubject());
        data.put("sha256Fingerprint", alert.certificateFingerprint());
        data.put("serialNumber", alert.serialNumber());
        data.put("notAfter", alert.notAfter() == null ? null : alert.notAfter().toString());
        data.put("daysUntilExpiry", alert.daysUntilExpiry());
        publisher.publish(type, data, alert.summary());
    }

    /** Only while an endpoint is enabled; otherwise every alert would be audited as sent through a channel that sent nothing. */
    @Override
    public boolean isActive() {
        if (!properties.isEnabled()) {
            return false;
        }
        Instant now = Instant.now(clock);
        if (activeCheckedAt.plusSeconds(30).isBefore(now)) {
            active = endpoints.countByEnabledTrue() > 0;
            activeCheckedAt = now;
        }
        return active;
    }

    /** Forgets whether an endpoint is enabled, after one is added or changed. */
    void endpointsChanged() {
        activeCheckedAt = Instant.EPOCH;
    }

    @Override
    public String channelName() {
        return "webhook";
    }

    @EventListener
    public void jobFinished(JobAudit.JobFinished finished) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("job", finished.job());
            data.put("outcome", finished.succeeded() ? "succeeded" : "failed");
            data.put("trigger", finished.trigger());
            data.put("durationMs", finished.durationMs());
            data.put("counts", finished.counts());
            if (finished.reason() != null) {
                data.put("reason", finished.reason());
            }
            String summary = finished.succeeded()
                    ? "CertAlert job %s completed in %d ms".formatted(finished.job(), finished.durationMs())
                    : "CertAlert job %s failed: %s".formatted(finished.job(), finished.reason());
            publisher.publish(finished.succeeded() ? WebhookEvent.JOB_COMPLETED : WebhookEvent.JOB_FAILED, data, summary);
        } catch (RuntimeException e) {
            log.warn("Could not queue the webhooks for the end of the {} run: {}", finished.job(), e.toString());
        }
    }
}

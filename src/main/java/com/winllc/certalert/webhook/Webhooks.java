package com.winllc.certalert.webhook;

import com.winllc.certalert.audit.AuditLog;
import com.winllc.certalert.audit.AuditRecord;
import com.winllc.certalert.service.ResourceNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The webhook endpoints an administrator configures: created with a secret shown once,
 * changed, given a new secret, deleted - each audited, and never the secret itself.
 */
@Service
public class Webhooks {

    private final WebhookEndpointRepository endpoints;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookSecrets secrets;
    private final WebhookAddressGuard guard;
    private final AuditLog audit;
    private final Clock clock;
    private final WebhookNotifications notifications;

    public Webhooks(
            WebhookEndpointRepository endpoints,
            WebhookDeliveryRepository deliveries,
            WebhookSecrets secrets,
            WebhookAddressGuard guard,
            AuditLog audit,
            Clock clock,
            WebhookNotifications notifications) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.secrets = secrets;
        this.guard = guard;
        this.audit = audit;
        this.clock = clock;
        this.notifications = notifications;
    }

    /** An endpoint and its secret, the one time the secret is available. */
    public record WithSecret(WebhookEndpoint endpoint, String secret) {}

    @Transactional
    public WithSecret create(String name, String url, WebhookEndpoint.Format format, Collection<WebhookEvent> events, String by) {
        String label = checkName(name);
        URI checked = checkUrl(url);
        Set<WebhookEvent> subscribed = checkEvents(events);
        Instant now = Instant.now(clock);
        WebhookEndpoint endpoint = new WebhookEndpoint(
                label, secrets.store(checked.toString()), format == null ? WebhookEndpoint.Format.JSON : format, subscribed, by, now);
        String secret = secrets.generate();
        endpoint.secret(secrets.store(secret), secret.substring(0, 12), now);
        endpoint = endpoints.save(endpoint);
        audit.record(AuditRecord.of(AuditRecord.Category.CONFIGURATION, "webhook.created")
                .severity(AuditRecord.Severity.NOTICE)
                .target("webhook", String.valueOf(endpoint.getId()), label)
                .detail("url", WebhookSecrets.redact(checked.toString()))
                .detail("format", endpoint.getFormat().name())
                .detail("events", WebhookEvent.format(subscribed)));
        notifications.endpointsChanged();
        return new WithSecret(endpoint, secret);
    }

    @Transactional
    public WebhookEndpoint update(
            Long id, String name, String url, WebhookEndpoint.Format format, Collection<WebhookEvent> events, boolean enabled) {
        WebhookEndpoint endpoint = find(id);
        String label = checkName(name);
        URI checked = checkUrl(url);
        Set<WebhookEvent> subscribed = checkEvents(events);
        AuditRecord record = AuditRecord.of(AuditRecord.Category.CONFIGURATION, "webhook.changed")
                .severity(AuditRecord.Severity.NOTICE)
                .target("webhook", String.valueOf(id), label)
                .change("url", WebhookSecrets.redact(secrets.revealUrl(endpoint)), WebhookSecrets.redact(checked.toString()))
                .change("events", WebhookEvent.format(endpoint.getEvents()), WebhookEvent.format(subscribed))
                .change("enabled", endpoint.isEnabled(), enabled);
        endpoint.update(label, secrets.store(checked.toString()), format == null ? endpoint.getFormat() : format, subscribed, enabled,
                Instant.now(clock));
        audit.record(record);
        notifications.endpointsChanged();
        return endpoint;
    }

    @Transactional
    public WithSecret rotateSecret(Long id) {
        WebhookEndpoint endpoint = find(id);
        String secret = secrets.generate();
        endpoint.secret(secrets.store(secret), secret.substring(0, 12), Instant.now(clock));
        audit.record(AuditRecord.of(AuditRecord.Category.CONFIGURATION, "webhook.secret-rotated")
                .severity(AuditRecord.Severity.NOTICE)
                .target("webhook", String.valueOf(id), endpoint.getName())
                .detail("prefix", endpoint.getSecretPrefix()));
        return new WithSecret(endpoint, secret);
    }

    @Transactional
    public void delete(Long id) {
        WebhookEndpoint endpoint = find(id);
        endpoints.delete(endpoint);
        audit.record(AuditRecord.of(AuditRecord.Category.CONFIGURATION, "webhook.deleted")
                .severity(AuditRecord.Severity.NOTICE)
                .target("webhook", String.valueOf(id), endpoint.getName())
                .detail("url", WebhookSecrets.redact(secrets.revealUrl(endpoint))));
        notifications.endpointsChanged();
    }

    @Transactional(readOnly = true)
    public List<WebhookEndpoint> list() {
        return endpoints.findAllByOrderByCreatedAtDesc();
    }

    /** The endpoint's URL as it was given: for an administrator, and for sending. */
    public String url(WebhookEndpoint endpoint) {
        return secrets.revealUrl(endpoint);
    }

    @Transactional(readOnly = true)
    public WebhookEndpoint find(Long id) {
        return endpoints.findById(id).orElseThrow(() -> new ResourceNotFoundException("No webhook " + id));
    }

    @Transactional(readOnly = true)
    public List<WebhookDelivery> deliveries(Long id, int limit) {
        find(id);
        return deliveries.findByEndpointIdOrderByCreatedAtDesc(id, PageRequest.of(0, Math.clamp(limit, 1, 200)));
    }

    private URI checkUrl(String url) {
        try {
            return guard.checkUrl(url);
        } catch (IOException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    private static String checkName(String name) {
        String label = name == null ? "" : name.trim();
        if (label.isEmpty() || label.length() > 120) {
            throw new IllegalArgumentException("Give the webhook a name of up to 120 characters");
        }
        return label;
    }

    private static Set<WebhookEvent> checkEvents(Collection<WebhookEvent> events) {
        Set<WebhookEvent> subscribed = events == null || events.isEmpty()
                ? EnumSet.noneOf(WebhookEvent.class) : EnumSet.copyOf(events);
        subscribed.remove(WebhookEvent.TEST);
        if (subscribed.isEmpty()) {
            throw new IllegalArgumentException("Choose at least one event for the webhook");
        }
        return subscribed;
    }
}

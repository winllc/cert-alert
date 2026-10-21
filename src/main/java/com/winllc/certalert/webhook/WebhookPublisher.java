package com.winllc.certalert.webhook;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes an event down for every enabled endpoint that wants it, in the caller's
 * transaction, to be posted once that commits (see {@link WebhookOutbox}).
 *
 * <p>What is written is the body exactly as it will be sent - CertAlert's JSON envelope, or a
 * Slack or Teams message - so the delivery log shows what the receiver got, and a retry
 * sends the same bytes (with a fresh timestamp and signature).
 */
@Service
public class WebhookPublisher {

    private final WebhookEndpointRepository endpoints;
    private final WebhookDeliveryRepository deliveries;
    private final WebhookOutbox outbox;
    private final WebhookProperties properties;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate joining;

    public WebhookPublisher(
            WebhookEndpointRepository endpoints,
            WebhookDeliveryRepository deliveries,
            WebhookOutbox outbox,
            WebhookProperties properties,
            ObjectMapper json,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
        this.outbox = outbox;
        this.properties = properties;
        this.json = json;
        this.clock = clock;
        this.joining = new TransactionTemplate(transactions);
    }

    /**
     * @param data the event's own fields
     * @param summary one line for a person: what a chat message says
     * @return how many endpoints it was queued for
     */
    public int publish(WebhookEvent type, Map<String, ?> data, String summary) {
        if (!properties.isEnabled()) {
            return 0;
        }
        Integer queued = joining.execute(status -> {
            List<WebhookEndpoint> wanting = endpoints.findByEnabledTrue().stream()
                    .filter(endpoint -> endpoint.wants(type))
                    .toList();
            if (wanting.isEmpty()) {
                return 0;
            }
            String eventId = UUID.randomUUID().toString();
            Instant now = Instant.now(clock);
            for (WebhookEndpoint endpoint : wanting) {
                deliveries.save(new WebhookDelivery(
                        endpoint.getId(), eventId, type, render(endpoint.getFormat(), eventId, type, now, data, summary), now));
            }
            return wanting.size();
        });
        if (queued != null && queued > 0) {
            outbox.sendAfterCommit();
        }
        return queued == null ? 0 : queued;
    }

    /** A test event to one endpoint, whatever it subscribes to and whether or not it is enabled. */
    public Long test(WebhookEndpoint endpoint, String requestedBy) {
        Long id = joining.execute(status -> {
            String eventId = UUID.randomUUID().toString();
            Instant now = Instant.now(clock);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("endpoint", endpoint.getName());
            data.put("requestedBy", requestedBy);
            String summary = "CertAlert test message for webhook \"" + endpoint.getName() + "\"";
            return deliveries.save(new WebhookDelivery(endpoint.getId(), eventId, WebhookEvent.TEST,
                    render(endpoint.getFormat(), eventId, WebhookEvent.TEST, now, data, summary), now)).getId();
        });
        outbox.sendAfterCommit();
        return id;
    }

    String render(
            WebhookEndpoint.Format format, String eventId, WebhookEvent type, Instant at, Map<String, ?> data, String summary) {
        Map<String, Object> body = new LinkedHashMap<>();
        switch (format) {
            case SLACK -> body.put("text", summary);
            case TEAMS -> {
                Map<String, Object> text = new LinkedHashMap<>();
                text.put("type", "TextBlock");
                text.put("text", summary);
                text.put("wrap", true);
                Map<String, Object> card = new LinkedHashMap<>();
                card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
                card.put("type", "AdaptiveCard");
                card.put("version", "1.4");
                card.put("body", List.of(text));
                Map<String, Object> attachment = new LinkedHashMap<>();
                attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
                attachment.put("content", card);
                body.put("type", "message");
                body.put("attachments", List.of(attachment));
            }
            case JSON -> {
                body.put("id", eventId);
                body.put("type", type.id());
                body.put("createdAt", at.toString());
                body.put("summary", summary);
                body.put("data", data);
            }
        }
        return json.writeValueAsString(body);
    }
}

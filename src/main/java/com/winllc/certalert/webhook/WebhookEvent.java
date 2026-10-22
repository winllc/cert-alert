package com.winllc.certalert.webhook;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** What a webhook can be told about. */
public enum WebhookEvent {

    /** A certificate has moved into its warning window. */
    CERTIFICATE_EXPIRING("certificate.expiring"),

    /** A certificate has expired. */
    CERTIFICATE_EXPIRED("certificate.expired"),

    /** A job - a sweep, a revocation check, the round-up - finished. */
    JOB_COMPLETED("job.completed"),

    /** A job failed. */
    JOB_FAILED("job.failed"),

    /** Sent when an administrator presses "Send a test"; every endpoint receives it whatever it subscribes to. */
    TEST("webhook.test");

    private final String id;

    WebhookEvent(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static WebhookEvent of(String id) {
        String value = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        for (WebhookEvent event : values()) {
            if (event.id.equals(value)) {
                return event;
            }
        }
        throw new IllegalArgumentException("'%s' is not a webhook event; the events are %s".formatted(
                id, Arrays.stream(values()).map(WebhookEvent::id).collect(Collectors.joining(", "))));
    }

    static Set<WebhookEvent> parse(String stored) {
        Set<WebhookEvent> events = EnumSet.noneOf(WebhookEvent.class);
        if (stored != null) {
            for (String part : stored.split(",")) {
                if (!part.isBlank()) {
                    events.add(of(part));
                }
            }
        }
        return events;
    }

    static String format(Set<WebhookEvent> events) {
        return events.stream().sorted().map(WebhookEvent::id).collect(Collectors.joining(","));
    }
}

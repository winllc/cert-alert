package com.winllc.certalert.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One event on its way to one endpoint, and what became of it: the outbox and the delivery
 * log in one row.
 */
@Entity
@Table(name = "webhook_delivery")
public class WebhookDelivery {

    public enum State {
        PENDING, SENDING, DELIVERED, FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "webhook_delivery_seq")
    @SequenceGenerator(name = "webhook_delivery_seq", sequenceName = "webhook_delivery_seq", allocationSize = 50)
    private Long id;

    @Column(name = "endpoint_id", nullable = false)
    private Long endpointId;

    /** The event's id, the same for every endpoint it goes to: a receiver's de-duplication key. */
    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state;

    /** The body, exactly as it is signed and sent. */
    @Column(nullable = false, length = 1_000_000)
    private String payload;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    /** The receiver's HTTP status on the last attempt, where it answered. */
    @Column(name = "last_status")
    private Integer lastStatus;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    protected WebhookDelivery() {
        // for JPA
    }

    WebhookDelivery(Long endpointId, String eventId, WebhookEvent type, String payload, Instant now) {
        this.endpointId = endpointId;
        this.eventId = eventId;
        this.eventType = type.id();
        this.payload = payload;
        this.state = State.PENDING;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getEndpointId() {
        return endpointId;
    }

    public String getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public State getState() {
        return state;
    }

    public String getPayload() {
        return payload;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Integer getLastStatus() {
        return lastStatus;
    }

    public String getLastError() {
        return lastError;
    }

    void delivered(int status, Instant at) {
        this.attempts++;
        this.state = State.DELIVERED;
        this.lastStatus = status;
        this.lastError = null;
        this.deliveredAt = at;
    }

    void failed(Integer status, String error, Instant retryAt) {
        this.attempts++;
        this.state = State.PENDING;
        this.lastStatus = status;
        this.lastError = clip(error);
        this.nextAttemptAt = retryAt;
    }

    void gaveUp(Integer status, String error) {
        this.attempts++;
        this.state = State.FAILED;
        this.lastStatus = status;
        this.lastError = clip(error);
    }

    private static String clip(String value) {
        return value == null || value.length() <= 1000 ? value : value.substring(0, 997) + "...";
    }
}

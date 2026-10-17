package com.winllc.certalert.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * How far one forwarding output has got through the store: the last seq it delivered, and
 * how its last attempt went. Persisted, so a restart resumes where delivery stopped.
 */
@Entity
@Table(name = "audit_delivery")
public class AuditDelivery {

    @Id
    @Column(length = 32)
    private String output;

    @Column(name = "last_seq", nullable = false)
    private long lastSeq;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "last_error_at")
    private Instant lastErrorAt;

    /** When the current run of failures began; null while delivery is working. */
    @Column(name = "failing_since")
    private Instant failingSince;

    /** Whether {@code audit.delivery-failed} has been recorded for the current run of failures. */
    @Column(nullable = false)
    private boolean alerted;

    protected AuditDelivery() {
        // for JPA
    }

    public AuditDelivery(String output, long lastSeq) {
        this.output = output;
        this.lastSeq = lastSeq;
    }

    public String getOutput() {
        return output;
    }

    public long getLastSeq() {
        return lastSeq;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getLastErrorAt() {
        return lastErrorAt;
    }

    public Instant getFailingSince() {
        return failingSince;
    }

    public boolean isAlerted() {
        return alerted;
    }

    void delivered(long seq, Instant at) {
        this.lastSeq = Math.max(lastSeq, seq);
        this.deliveredAt = at;
        this.failingSince = null;
    }

    void failed(String error, Instant at) {
        this.lastError = error == null ? null : error.length() > 1000 ? error.substring(0, 1000) : error;
        this.lastErrorAt = at;
        if (failingSince == null) {
            failingSince = at;
        }
    }

    void setAlerted(boolean alerted) {
        this.alerted = alerted;
    }
}

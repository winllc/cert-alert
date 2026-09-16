package com.winllc.certalert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * A TLS endpoint whose certificate is monitored, together with a denormalised summary of
 * its most recent check so list views do not have to join the full check history.
 */
@Entity
@Table(
        name = "certificate_target",
        uniqueConstraints = @UniqueConstraint(name = "uk_certificate_target_name", columnNames = "name"))
public class CertificateTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 255)
    private String hostname;

    @Column(nullable = false)
    private int port = 443;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false)
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_status", length = 32)
    private CheckStatus lastStatus;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "last_expires_at")
    private Instant lastExpiresAt;

    @Column(name = "last_alerted_at")
    private Instant lastAlertedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_alerted_status", length = 32)
    private CheckStatus lastAlertedStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected CertificateTarget() {
        // for JPA
    }

    public CertificateTarget(String name, String hostname, int port) {
        this.name = name;
        this.hostname = hostname;
        this.port = port;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    /** Records the outcome of a check on this target. */
    public void applyCheckResult(CertificateCheck check) {
        this.lastStatus = check.getStatus();
        this.lastCheckedAt = check.getCheckedAt();
        this.lastExpiresAt = check.getNotAfter();
    }

    /** Marks that an alert has just been dispatched for the given status. */
    public void recordAlert(CheckStatus status, Instant sentAt) {
        this.lastAlertedStatus = status;
        this.lastAlertedAt = sentAt;
    }

    /** Clears alert bookkeeping, so the next unhealthy state alerts immediately. */
    public void clearAlertState() {
        this.lastAlertedStatus = null;
        this.lastAlertedAt = null;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public CheckStatus getLastStatus() {
        return lastStatus;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public Instant getLastExpiresAt() {
        return lastExpiresAt;
    }

    public Instant getLastAlertedAt() {
        return lastAlertedAt;
    }

    public CheckStatus getLastAlertedStatus() {
        return lastAlertedStatus;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}

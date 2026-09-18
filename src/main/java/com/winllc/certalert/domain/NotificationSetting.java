package com.winllc.certalert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * How far ahead the expiry round-up looks, as somebody set it here.
 *
 * <p>One row, with a fixed id rather than a sequence: there is one answer for the
 * deployment, not one per person. A round-up goes to a point of contact, who may be a
 * distribution list that never signs in, so there is nobody to hold a preference against.
 *
 * <p>No row at all means nobody has set it, and the configured default stands. That is
 * deliberately different from a row holding the same number: it says whether the deployment
 * has had this decision made about it.
 */
@Entity
@Table(name = "notification_setting")
public class NotificationSetting {

    /** The only row there is. */
    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id = SINGLETON_ID;

    /** Days before expiry at which the round-up starts reporting a certificate. */
    @Column(name = "lead_days", nullable = false)
    private int leadDays;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Who set it, as the directory names them. */
    @Column(name = "updated_by", length = 320)
    private String updatedBy;

    protected NotificationSetting() {}

    public NotificationSetting(int leadDays, String updatedBy, Instant updatedAt) {
        this.id = SINGLETON_ID;
        this.leadDays = leadDays;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public void update(int leadDays, String updatedBy, Instant updatedAt) {
        this.leadDays = leadDays;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
    }

    public int getLeadDays() {
        return leadDays;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }
}

package com.winllc.certalert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * An immutable record of one certificate inspection, kept as history so trends and past
 * failures remain visible after the target's summary has moved on.
 */
@Entity
@Table(
        name = "certificate_check",
        indexes = {
            @Index(name = "idx_certificate_check_target", columnList = "target_id"),
            @Index(name = "idx_certificate_check_checked_at", columnList = "checked_at")
        })
public class CertificateCheck {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_id", nullable = false, foreignKey = @jakarta.persistence.ForeignKey(name = "fk_certificate_check_target"))
    private CertificateTarget target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CheckStatus status;

    @Column(name = "checked_at", nullable = false)
    private Instant checkedAt;

    @Column(length = 500)
    private String subject;

    @Column(length = 500)
    private String issuer;

    @Column(name = "serial_number", length = 100)
    private String serialNumber;

    @Column(name = "not_before")
    private Instant notBefore;

    @Column(name = "not_after")
    private Instant notAfter;

    @Column(name = "days_until_expiry")
    private Long daysUntilExpiry;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    protected CertificateCheck() {
        // for JPA
    }

    private CertificateCheck(CertificateTarget target, CheckStatus status, Instant checkedAt) {
        this.target = target;
        this.status = status;
        this.checkedAt = checkedAt;
    }

    /** Creates a check for an endpoint whose certificate was retrieved successfully. */
    public static CertificateCheck success(
            CertificateTarget target,
            CheckStatus status,
            Instant checkedAt,
            String subject,
            String issuer,
            String serialNumber,
            Instant notBefore,
            Instant notAfter,
            long daysUntilExpiry) {
        CertificateCheck check = new CertificateCheck(target, status, checkedAt);
        check.subject = subject;
        check.issuer = issuer;
        check.serialNumber = serialNumber;
        check.notBefore = notBefore;
        check.notAfter = notAfter;
        check.daysUntilExpiry = daysUntilExpiry;
        return check;
    }

    /** Creates a check for an endpoint that could not be inspected. */
    public static CertificateCheck failure(CertificateTarget target, Instant checkedAt, String errorMessage) {
        CertificateCheck check = new CertificateCheck(target, CheckStatus.UNREACHABLE, checkedAt);
        check.errorMessage = truncate(errorMessage, 1000);
        return check;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    public Long getId() {
        return id;
    }

    public CertificateTarget getTarget() {
        return target;
    }

    public CheckStatus getStatus() {
        return status;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }

    public String getSubject() {
        return subject;
    }

    public String getIssuer() {
        return issuer;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public Instant getNotBefore() {
        return notBefore;
    }

    public Instant getNotAfter() {
        return notAfter;
    }

    public Long getDaysUntilExpiry() {
        return daysUntilExpiry;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}

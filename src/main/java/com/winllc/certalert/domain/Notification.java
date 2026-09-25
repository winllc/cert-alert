package com.winllc.certalert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Something somebody needs to be told, addressed to one person.
 *
 * <p>An alert is raised once about a certificate; a notification is one person's copy of
 * it. A server with four points of contact produces four, because being told is a thing
 * that happens to a person, and so is having read it.
 *
 * <p>The recipient is held twice over. {@code recipientUserId} is the person in the
 * directory, which is what makes it appear when they sign in; {@code recipientAddress} is
 * where an email would go. A distribution list with nobody claiming it has the second and
 * not the first: it can be written to and cannot be read on the page by anybody.
 */
@Entity
@Table(
        name = "notification",
        indexes = {
            @Index(name = "idx_notification_recipient", columnList = "recipient_user_id, created_at"),
            @Index(name = "idx_notification_unread", columnList = "recipient_user_id, read_at"),
            @Index(name = "idx_notification_unsent", columnList = "emailed_at, created_at"),
            @Index(name = "idx_notification_subject", columnList = "subject_type, subject_id")
        })
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "notification_seq")
    @SequenceGenerator(name = "notification_seq", sequenceName = "notification_seq", allocationSize = 50)
    private Long id;

    /** The person in the directory, when the recipient is one. Null for a bare address. */
    @Column(name = "recipient_user_id")
    private Long recipientUserId;

    /** Where an email would go. Null when the directory publishes no address for them. */
    @Column(name = "recipient_address", length = 320)
    private String recipientAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    private OwnerType subjectType;

    @Column(name = "subject_id")
    private Long subjectId;

    @Column(name = "subject_dn", nullable = false, length = 512)
    private String subjectDn;

    @Column(name = "subject_name", length = 320)
    private String subjectName;

    @Column(name = "certificate_fingerprint", length = 64)
    private String certificateFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity = Severity.INFO;

    @Column(nullable = false, length = 1000)
    private String message;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** When they read it. Null while it is still waiting to be seen. */
    @Column(name = "read_at")
    private Instant readAt;

    /** When it went out by email. Null while it has not, which is what the digest looks for. */
    @Column(name = "emailed_at")
    private Instant emailedAt;

    protected Notification() {
        // for JPA
    }

    public Notification(
            Long recipientUserId,
            String recipientAddress,
            NotificationKind kind,
            AuditEvent.SubjectRef subject,
            String certificateFingerprint,
            Severity severity,
            String message,
            Instant createdAt) {
        this.recipientUserId = recipientUserId;
        this.recipientAddress = recipientAddress;
        this.kind = kind;
        this.subjectType = subject.type();
        this.subjectId = subject.id();
        this.subjectDn = subject.dn();
        this.subjectName = subject.name();
        this.certificateFingerprint = certificateFingerprint;
        this.severity = severity;
        this.message = message;
        this.createdAt = createdAt;
    }

    /**
     * Says the same thing again, with what is expiring now.
     *
     * <p>A round-up is a report of the current state rather than a record that something
     * happened, so a person has one of them rather than one per run: a nightly job that
     * left a fresh copy behind every night would bury the page in identical rows, and the
     * page is where somebody looks to find out what they have to do.
     *
     * <p>Called only when the wording has actually changed, and it then reads as unread
     * again - something is expiring that was not before, or something has been renewed, and
     * either is worth another look. An unchanged round-up is left exactly as it is, read or
     * not: marking it unread every night is how a notification becomes furniture.
     */
    public void refresh(Severity severity, String message, Instant when) {
        this.severity = severity;
        this.message = message;
        this.createdAt = when;
        this.readAt = null;
    }

    public void markRead(Instant when) {
        if (readAt == null) {
            this.readAt = when;
        }
    }

    public void markEmailed(Instant when) {
        this.emailedAt = when;
    }

    public boolean isUnread() {
        return readAt == null;
    }

    public Long getId() {
        return id;
    }

    public Long getRecipientUserId() {
        return recipientUserId;
    }

    public String getRecipientAddress() {
        return recipientAddress;
    }

    public NotificationKind getKind() {
        return kind;
    }

    public OwnerType getSubjectType() {
        return subjectType;
    }

    public Long getSubjectId() {
        return subjectId;
    }

    public String getSubjectDn() {
        return subjectDn;
    }

    public String getSubjectName() {
        return subjectName;
    }

    public String getCertificateFingerprint() {
        return certificateFingerprint;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public Instant getEmailedAt() {
        return emailedAt;
    }
}

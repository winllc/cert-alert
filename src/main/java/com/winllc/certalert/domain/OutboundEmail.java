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
import java.util.Arrays;
import java.util.List;

/**
 * One email, from the moment something decided to send it until it went or was given up on.
 *
 * <p>Written in the same transaction as whatever decided to send it, and sent after that
 * commits - so a mail server that is down loses nothing, and one that is slow holds no
 * database transaction open while it thinks. See {@code MailOutbox}.
 */
@Entity
@Table(
        name = "outbound_email",
        indexes = {
            @Index(name = "idx_outbound_email_due", columnList = "state, next_attempt_at"),
            @Index(name = "idx_outbound_email_supersede", columnList = "supersede_key, state"),
            @Index(name = "idx_outbound_email_created", columnList = "created_at")
        })
public class OutboundEmail {

    /** Long enough for any round-up anybody would read; bodies longer are cut. */
    public static final int MAX_BODY = 1_000_000;

    /** What sent it, for the logs and for reading the table. */
    public enum Kind {
        /** A point of contact's round-up of what is expiring. */
        DIGEST,
        /** An alert to the fixed list of operators. */
        ALERT
    }

    /** Where it has got to. */
    public enum State {
        /** Not sent yet: due at {@code nextAttemptAt}. */
        PENDING,
        SENT,
        /** Tried until it was too old to be worth sending, and given up on. */
        FAILED,
        /** A newer version of the same message was queued before this one went. */
        SUPERSEDED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "outbound_email_seq")
    @SequenceGenerator(name = "outbound_email_seq", sequenceName = "outbound_email_seq", allocationSize = 50)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state = State.PENDING;

    @Column(nullable = false, length = 320)
    private String sender;

    /** Comma-separated. */
    @Column(nullable = false, length = 4000)
    private String recipients;

    @Column(nullable = false, length = 1000)
    private String subject;

    @Column(name = "text_body", nullable = false, length = MAX_BODY)
    private String textBody;

    @Column(name = "html_body", length = MAX_BODY)
    private String htmlBody;

    @Column(name = "supersede_key", length = 512)
    private String supersedeKey;

    /** The round-up this delivers, which is marked emailed when it goes. */
    @Column(name = "notification_id")
    private Long notificationId;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    protected OutboundEmail() {
        // for JPA
    }

    public OutboundEmail(
            Kind kind,
            String sender,
            List<String> recipients,
            String subject,
            String textBody,
            String htmlBody,
            String supersedeKey,
            Long notificationId,
            Instant createdAt) {
        this.kind = kind;
        this.sender = sender;
        this.recipients = String.join(",", recipients);
        this.subject = clip(subject, 1000);
        this.textBody = clip(textBody, MAX_BODY);
        // An HTML body too long to store goes as plain text rather than as broken HTML.
        this.htmlBody = htmlBody == null || htmlBody.length() > MAX_BODY ? null : htmlBody;
        this.supersedeKey = clip(supersedeKey, 512);
        this.notificationId = notificationId;
        this.createdAt = createdAt;
        this.nextAttemptAt = createdAt;
    }

    public void sent(Instant when) {
        this.state = State.SENT;
        this.attempts++;
        this.sentAt = when;
        this.lastError = null;
    }

    /** A failed attempt, and when to try again. */
    public void failed(String error, Instant retryAt) {
        this.attempts++;
        this.lastError = clip(error, 1000);
        this.nextAttemptAt = retryAt;
    }

    public void gaveUp(String error) {
        this.state = State.FAILED;
        this.attempts++;
        this.lastError = clip(error, 1000);
    }

    public Long getId() {
        return id;
    }

    public Kind getKind() {
        return kind;
    }

    public State getState() {
        return state;
    }

    public String getSender() {
        return sender;
    }

    public List<String> getRecipients() {
        return Arrays.stream(recipients.split(",")).map(String::trim).filter(r -> !r.isEmpty()).toList();
    }

    public String getSubject() {
        return subject;
    }

    public String getTextBody() {
        return textBody;
    }

    public String getHtmlBody() {
        return htmlBody;
    }

    public String getSupersedeKey() {
        return supersedeKey;
    }

    public Long getNotificationId() {
        return notificationId;
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

    public Instant getSentAt() {
        return sentAt;
    }

    public String getLastError() {
        return lastError;
    }

    private static String clip(String value, int limit) {
        return value == null || value.length() <= limit ? value : value.substring(0, limit);
    }
}

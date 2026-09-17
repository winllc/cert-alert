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
 * Something that happened to a directory entry, kept so it can be answered for later.
 *
 * <p>The subject is held as a type and an id with <em>no foreign key</em>, on purpose. An
 * audit record must outlive what it describes: the most interesting record of all is the
 * one saying an entry was deleted, and a foreign key would either delete that record along
 * with the entry or refuse the deletion. The distinguished name and display name are
 * copied in for the same reason - after a prune they are all that is left to read.
 */
@Entity
@Table(
        name = "audit_event",
        indexes = {
            @Index(name = "idx_audit_event_subject", columnList = "subject_type, subject_id, occurred_at"),
            @Index(name = "idx_audit_event_occurred", columnList = "occurred_at"),
            @Index(name = "idx_audit_event_action", columnList = "action")
        })
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "audit_event_seq")
    @SequenceGenerator(name = "audit_event_seq", sequenceName = "audit_event_seq", allocationSize = 50)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    private OwnerType subjectType;

    /** The entry this is about, while it still exists. */
    @Column(name = "subject_id")
    private Long subjectId;

    @Column(name = "subject_dn", nullable = false, length = 512)
    private String subjectDn;

    @Column(name = "subject_name", length = 320)
    private String subjectName;

    /**
     * Who did it: whoever was signed in, or the job that did it - see
     * {@code AuditActors}. Never null, because "something changed and nobody knows what"
     * is not an audit record.
     */
    @Column(nullable = false, length = 320)
    private String actor;

    @Column(nullable = false, length = 1000)
    private String summary;

    /** Set where the record is about one certificate, so its history can be followed. */
    @Column(name = "certificate_fingerprint", length = 64)
    private String certificateFingerprint;

    /** Set on a delivery: which channel took it. */
    @Column(length = 64)
    private String channel;

    /** Set where something was acted on by name: an address written to, a contact added. */
    @Column(length = 320)
    private String target;

    protected AuditEvent() {
        // for JPA
    }

    private AuditEvent(
            AuditAction action,
            OwnerType subjectType,
            Long subjectId,
            String subjectDn,
            String subjectName,
            String summary,
            Instant occurredAt) {
        this.action = action;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.subjectDn = subjectDn;
        this.subjectName = subjectName;
        this.summary = summary;
        this.occurredAt = occurredAt;
    }

    /** Every record is about a subject, and says what happened to it and when. */
    public static AuditEvent about(SubjectRef subject, AuditAction action, String summary, Instant when) {
        return new AuditEvent(action, subject.type(), subject.id(), subject.dn(), subject.name(), summary, when);
    }

    public AuditEvent by(String actor) {
        this.actor = actor;
        return this;
    }

    public AuditEvent forCertificate(String fingerprint) {
        this.certificateFingerprint = fingerprint;
        return this;
    }

    public AuditEvent through(String channel) {
        this.channel = channel;
        return this;
    }

    public AuditEvent to(String target) {
        this.target = target;
        return this;
    }

    private static String displayNameOf(DirectoryUser user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        return user.getCommonName() != null ? user.getCommonName() : user.getUid();
    }

    /** A subject named without holding the entity, for records written after it is gone. */
    public record SubjectRef(OwnerType type, Long id, String dn, String name) {

        public static SubjectRef of(DirectoryUser user) {
            return new SubjectRef(OwnerType.USER, user.getId(), user.getDn(), displayNameOf(user));
        }

        public static SubjectRef of(DirectoryServer server) {
            String name = server.getCommonName() != null ? server.getCommonName() : server.getDn();
            return new SubjectRef(OwnerType.SERVER, server.getId(), server.getDn(), name);
        }
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public AuditAction getAction() {
        return action;
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

    public String getActor() {
        return actor;
    }

    public String getSummary() {
        return summary;
    }

    public String getCertificateFingerprint() {
        return certificateFingerprint;
    }

    public String getChannel() {
        return channel;
    }

    public String getTarget() {
        return target;
    }
}

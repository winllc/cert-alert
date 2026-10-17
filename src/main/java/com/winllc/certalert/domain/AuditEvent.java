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
 *
 * <p>Every record is also an {@code ens-audit/1} event: the shared ENS audit format, numbered
 * by {@code seq} without gaps and chained by SHA-256. The columns from {@code seq} down are
 * that event's searchable fields, and {@code line} is the event itself, exactly as it was
 * emitted - the source of truth for verification and for every output. Records about an
 * entry carry both; events that are about no entry (a sign-in, a settings change) carry only
 * the second. Nothing in the application updates or deletes a record once it is written,
 * other than retention pruning. See {@code com.winllc.certalert.audit.AuditLog}.
 */
@Entity
@Table(
        name = "audit_event",
        indexes = {
            @Index(name = "idx_audit_event_subject", columnList = "subject_type, subject_id, occurred_at"),
            @Index(name = "idx_audit_event_occurred", columnList = "occurred_at"),
            @Index(name = "idx_audit_event_action", columnList = "action"),
            @Index(name = "idx_audit_event_category", columnList = "category"),
            @Index(name = "idx_audit_event_event_action", columnList = "event_action"),
            @Index(name = "idx_audit_event_outcome", columnList = "outcome"),
            @Index(name = "idx_audit_event_severity", columnList = "severity"),
            @Index(name = "idx_audit_event_actor_id", columnList = "actor_id"),
            @Index(name = "idx_audit_event_target", columnList = "target_type, target_id"),
            @Index(name = "idx_audit_event_source_ip", columnList = "source_ip")
        })
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "audit_event_seq")
    @SequenceGenerator(name = "audit_event_seq", sequenceName = "audit_event_seq", allocationSize = 50)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** What happened to the entry; null on an event that is about no entry. */
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", length = 16)
    private OwnerType subjectType;

    /** The entry this is about, while it still exists. */
    @Column(name = "subject_id")
    private Long subjectId;

    @Column(name = "subject_dn", length = 512)
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

    @Column(length = 1000)
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

    // --- ens-audit/1 --------------------------------------------------------------------

    /** Position in the chain: gapless, increasing, from 1. Null only before migration. */
    @Column(unique = true)
    private Long seq;

    /** The event's own id, a UUID: what a SIEM de-duplicates on. */
    @Column(name = "event_id", length = 36)
    private String eventId;

    @Column(length = 16)
    private String category;

    /** The dotted action id, {@code auth.login}, {@code entry.discovered}. */
    @Column(name = "event_action", length = 80)
    private String eventAction;

    @Column(length = 8)
    private String outcome;

    @Column(length = 8)
    private String severity;

    @Column(name = "actor_type", length = 16)
    private String actorType;

    @Column(name = "actor_id", length = 320)
    private String actorId;

    @Column(name = "target_type", length = 32)
    private String targetType;

    @Column(name = "target_id", length = 512)
    private String targetId;

    @Column(name = "source_ip", length = 64)
    private String sourceIp;

    @Column(length = 64)
    private String hash;

    /** The event as emitted, hash included, verbatim. */
    @Column(length = 100000)
    private String line;

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

    /** An event that is about no directory entry: a sign-in, a settings change, a job run. */
    public static AuditEvent event(Instant when, String actor) {
        AuditEvent event = new AuditEvent();
        event.occurredAt = when;
        event.actor = actor;
        return event;
    }

    /**
     * Fills in the {@code ens-audit/1} side once the event has its place in the chain. Called
     * by the writer alone, once, before the row is first written - or, for a row from before
     * the format, by the one-off migration that numbers it.
     */
    public void chain(
            long seq,
            String eventId,
            String category,
            String eventAction,
            String outcome,
            String severity,
            String actorType,
            String actorId,
            String targetType,
            String targetId,
            String sourceIp,
            String hash,
            String line) {
        if (this.seq != null) {
            throw new IllegalStateException("Audit event " + this.seq + " is already chained");
        }
        this.seq = seq;
        this.eventId = eventId;
        this.category = category;
        this.eventAction = eventAction;
        this.outcome = outcome;
        this.severity = severity;
        this.actorType = actorType;
        this.actorId = clip(actorId, 320);
        this.targetType = clip(targetType, 32);
        this.targetId = clip(targetId, 512);
        this.sourceIp = clip(sourceIp, 64);
        this.hash = hash;
        this.line = line;
        if (this.actor == null) {
            this.actor = actorId != null ? clip(actorId, 320) : actorType;
        }
    }

    private static String clip(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
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

    public Long getSeq() {
        return seq;
    }

    public String getEventId() {
        return eventId;
    }

    public String getCategory() {
        return category;
    }

    public String getEventAction() {
        return eventAction;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getSeverity() {
        return severity;
    }

    public String getActorType() {
        return actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public String getHash() {
        return hash;
    }

    public String getLine() {
        return line;
    }
}

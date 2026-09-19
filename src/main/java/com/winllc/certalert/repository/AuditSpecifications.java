package com.winllc.certalert.repository;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.OwnerType;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * The filters the audit tables apply on top of DataTables' own search - the one on a
 * details page, which is always narrowed to that entry, and the one on the administration
 * page, which is narrowed to nothing until somebody narrows it.
 */
public final class AuditSpecifications {

    private AuditSpecifications() {}

    /**
     * One entry's records and nobody else's. Always applied: the table is on a details page,
     * and a page about one server has no business showing what happened to another.
     */
    public static Specification<AuditEvent> subject(OwnerType subjectType, Long subjectId) {
        return (root, query, builder) -> builder.and(
                builder.equal(root.get("subjectType"), subjectType), builder.equal(root.get("subjectId"), subjectId));
    }

    /** People or servers, without naming one of them. */
    public static Specification<AuditEvent> subjectTypeIs(OwnerType subjectType) {
        if (subjectType == null) {
            return (root, query, builder) -> null;
        }
        return (root, query, builder) -> builder.equal(root.get("subjectType"), subjectType);
    }

    /**
     * What it was about, by name or by distinguished name - the two the record carries,
     * copied in when it was written so they survive the entry being pruned.
     */
    public static Specification<AuditEvent> subjectLike(String term) {
        if (term == null || term.isBlank()) {
            return (root, query, builder) -> null;
        }
        String pattern = "%" + term.trim().toLowerCase(Locale.ROOT) + "%";
        return (root, query, builder) -> builder.or(
                builder.like(builder.lower(root.get("subjectName")), pattern),
                builder.like(builder.lower(root.get("subjectDn")), pattern));
    }

    /**
     * Who did it, matched on part of the name: a person as the directory names them, or one
     * of the jobs - "sync", "changelog", "expiry refresh", "prune".
     */
    public static Specification<AuditEvent> actorLike(String term) {
        if (term == null || term.isBlank()) {
            return (root, query, builder) -> null;
        }
        String pattern = "%" + term.trim().toLowerCase(Locale.ROOT) + "%";
        return (root, query, builder) -> builder.like(builder.lower(root.get("actor")), pattern);
    }

    /**
     * What happened inside a stretch of time. Either end may be left open, and the upper
     * one is exclusive - the caller passes the start of the day after the one it means.
     */
    public static Specification<AuditEvent> occurredBetween(Instant from, Instant to) {
        if (from == null && to == null) {
            return (root, query, builder) -> null;
        }
        return (root, query, builder) -> {
            if (from == null) {
                return builder.lessThan(root.get("occurredAt"), to);
            }
            if (to == null) {
                return builder.greaterThanOrEqualTo(root.get("occurredAt"), from);
            }
            return builder.and(
                    builder.greaterThanOrEqualTo(root.get("occurredAt"), from),
                    builder.lessThan(root.get("occurredAt"), to));
        };
    }

    /** Narrowed to particular kinds of event: what was sent, what was edited. */
    public static Specification<AuditEvent> actionIn(Collection<AuditAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return (root, query, builder) -> null;
        }
        return (root, query, builder) -> root.get("action").in(actions);
    }
}

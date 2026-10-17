package com.winllc.certalert.audit;

import com.winllc.certalert.domain.AuditEvent;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * The audit page's filters: a stretch of time, category, action, outcome, severity, actor and
 * target (contains), source address, and free text over the event's JSON. Every one optional.
 *
 * @param from inclusive
 * @param to exclusive
 */
public record AuditQuery(
        Instant from,
        Instant to,
        String category,
        String action,
        String outcome,
        String severity,
        String actor,
        String target,
        String sourceIp,
        String text) {

    public static AuditQuery all() {
        return new AuditQuery(null, null, null, null, null, null, null, null, null, null);
    }

    /** Only chained events: anything without a seq is history not yet migrated. */
    public Specification<AuditEvent> toSpecification() {
        return (root, query, builder) -> {
            List<Predicate> where = new ArrayList<>();
            where.add(builder.isNotNull(root.get("seq")));
            if (from != null) {
                where.add(builder.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                where.add(builder.lessThan(root.get("occurredAt"), to));
            }
            equal(where, builder, root.get("category"), category);
            equal(where, builder, root.get("eventAction"), action);
            equal(where, builder, root.get("outcome"), outcome);
            equal(where, builder, root.get("severity"), severity);
            if (present(sourceIp)) {
                where.add(builder.equal(root.get("sourceIp"), sourceIp.trim()));
            }
            if (present(actor)) {
                String pattern = like(actor);
                where.add(builder.or(
                        builder.like(builder.lower(root.get("actorId")), pattern),
                        builder.like(builder.lower(root.get("actor")), pattern)));
            }
            if (present(target)) {
                String pattern = like(target);
                where.add(builder.or(
                        builder.like(builder.lower(root.get("targetId")), pattern),
                        builder.like(builder.lower(root.get("subjectName")), pattern),
                        builder.like(builder.lower(root.get("targetType")), pattern)));
            }
            if (present(text)) {
                where.add(builder.like(builder.lower(root.get("line")), like(text)));
            }
            return builder.and(where.toArray(Predicate[]::new));
        };
    }

    /** The filter as the export records it. */
    public String describe() {
        StringBuilder out = new StringBuilder();
        append(out, "from", from == null ? null : AuditFormat.time(from));
        append(out, "to", to == null ? null : AuditFormat.time(to));
        append(out, "category", category);
        append(out, "action", action);
        append(out, "outcome", outcome);
        append(out, "severity", severity);
        append(out, "actor", actor);
        append(out, "target", target);
        append(out, "sourceIp", sourceIp);
        append(out, "text", text);
        return out.isEmpty() ? "all" : out.toString();
    }

    private static void append(StringBuilder out, String name, String value) {
        if (present(value)) {
            if (!out.isEmpty()) {
                out.append("; ");
            }
            out.append(name).append('=').append(value);
        }
    }

    private static void equal(
            List<Predicate> where,
            jakarta.persistence.criteria.CriteriaBuilder builder,
            jakarta.persistence.criteria.Path<String> path,
            String value) {
        if (present(value)) {
            where.add(builder.equal(path, value.trim().toLowerCase(Locale.ROOT)));
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String like(String value) {
        String escaped = value.trim().toLowerCase(Locale.ROOT);
        return "%" + escaped + "%";
    }
}

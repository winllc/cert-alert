package com.winllc.certalert.repository;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.OwnerType;
import java.util.Collection;
import org.springframework.data.jpa.domain.Specification;

/** The filters the audit table on a details page applies on top of DataTables' own search. */
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

    /** Narrowed to particular kinds of event: what was sent, what was edited. */
    public static Specification<AuditEvent> actionIn(Collection<AuditAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return (root, query, builder) -> null;
        }
        return (root, query, builder) -> root.get("action").in(actions);
    }
}

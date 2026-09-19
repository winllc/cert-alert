package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.OwnerType;
import java.time.Instant;

/**
 * One line of an entry's history.
 *
 * @param subjectType what it happened to, a person or a server
 * @param subjectId that entry while it still exists, so the row can link to it
 * @param subjectDn and its name as they were when it happened, which is all that is left
 *     after a prune
 * @param action what kind of thing happened, for the badge
 * @param label how that reads
 * @param summary the sentence
 * @param actor who did it: a person, or the job that did
 * @param certificateFingerprint set where it is about one certificate
 * @param channel set where it is a delivery
 * @param target set where something was acted on by name
 */
public record AuditEventRow(
        Long id,
        Instant occurredAt,
        OwnerType subjectType,
        Long subjectId,
        String subjectDn,
        String subjectName,
        AuditAction action,
        String label,
        String summary,
        String actor,
        String certificateFingerprint,
        String channel,
        String target) {

    public static AuditEventRow from(AuditEvent event) {
        return new AuditEventRow(
                event.getId(),
                event.getOccurredAt(),
                event.getSubjectType(),
                event.getSubjectId(),
                event.getSubjectDn(),
                event.getSubjectName(),
                event.getAction(),
                event.getAction().label(),
                event.getSummary(),
                event.getActor(),
                event.getCertificateFingerprint(),
                event.getChannel(),
                event.getTarget());
    }
}

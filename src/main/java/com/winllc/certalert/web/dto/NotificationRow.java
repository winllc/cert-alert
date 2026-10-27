package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.Notification;
import com.winllc.certalert.domain.NotificationKind;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.Severity;
import com.winllc.certalert.service.NotificationService;
import java.time.Instant;

/**
 * One notification as somebody sees it.
 *
 * @param subjectType what it is about, so the row can link to that entry's page
 * @param subjectId null once the entry it was about has been pruned
 * @param link where to send them, or null when there is nothing left to open
 */
public record NotificationRow(
        Long id,
        NotificationKind kind,
        String kindLabel,
        Severity severity,
        String message,
        OwnerType subjectType,
        Long subjectId,
        String subjectName,
        String link,
        boolean unread,
        Instant createdAt,
        Instant readAt,
        Instant emailedAt) {

    public static NotificationRow from(Notification notification) {
        return new NotificationRow(
                notification.getId(),
                notification.getKind(),
                notification.getKind().label(),
                notification.getSeverity(),
                notification.getMessage(),
                notification.getSubjectType(),
                notification.getSubjectId(),
                legacyRoundUp(notification) ? null : notification.getSubjectName(),
                link(notification),
                notification.isUnread(),
                notification.getCreatedAt(),
                notification.getReadAt(),
                notification.getEmailedAt());
    }

    /**
     * A round-up saved before round-ups named their entry: its subject is the person reading
     * it, which is not what it is about, so it names and links to nothing until the next
     * round-up brings it up to date. One naming several entries has no id, and links nowhere.
     */
    private static String link(Notification notification) {
        if (legacyRoundUp(notification) || notification.getSubjectId() == null) {
            return null;
        }
        return (notification.getSubjectType() == OwnerType.USER ? "/users/" : "/servers/") + notification.getSubjectId();
    }

    private static boolean legacyRoundUp(Notification notification) {
        return notification.getKind() == NotificationKind.EXPIRY_DIGEST
                && NotificationService.LEGACY_ROUND_UP_DN.equals(notification.getSubjectDn());
    }
}

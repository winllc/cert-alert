package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.Notification;
import com.winllc.certalert.domain.NotificationKind;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.Severity;
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
                notification.getSubjectName(),
                link(notification),
                notification.isUnread(),
                notification.getCreatedAt(),
                notification.getReadAt(),
                notification.getEmailedAt());
    }

    /** A round-up is about many entries at once, so it links to none of them. */
    private static String link(Notification notification) {
        if (notification.getKind() == NotificationKind.EXPIRY_DIGEST || notification.getSubjectId() == null) {
            return null;
        }
        return (notification.getSubjectType() == OwnerType.USER ? "/users/" : "/servers/") + notification.getSubjectId();
    }
}

package com.winllc.certalert.alert;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.service.AuditActors;
import com.winllc.certalert.service.AuditService;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fans an alert out to every configured notifier, isolating channel failures.
 *
 * <p>Every attempt is audited, successful or not, against the entry the certificate belongs
 * to. "Was anybody told, and when" is the question this exists to answer, and a channel
 * that threw is a more important answer than one that did not.
 */
@Component
public class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    private final List<AlertNotifier> notifiers;
    private final AuditService auditService;

    public AlertDispatcher(List<AlertNotifier> notifiers, AuditService auditService) {
        this.notifiers = notifiers;
        this.auditService = auditService;
    }

    public void dispatch(CertificateAlert alert) {
        if (notifiers.isEmpty()) {
            log.warn("No alert notifiers configured, dropping alert: {}", alert.summary());
            return;
        }
        List<AuditEvent> records = new ArrayList<>(notifiers.size());
        String actor = AuditActors.current(AuditActors.SYSTEM);

        for (AlertNotifier notifier : notifiers) {
            try {
                notifier.send(alert);
                records.add(record(alert, AuditAction.ALERT_SENT, notifier.channelName(), actor, null));
            } catch (RuntimeException e) {
                log.error("Alert channel '{}' failed to deliver alert for target '{}'",
                        notifier.channelName(), alert.ownerName(), e);
                records.add(record(alert, AuditAction.ALERT_FAILED, notifier.channelName(), actor, e.toString()));
            }
        }
        auditService.recordAll(records);
    }

    private AuditEvent record(
            CertificateAlert alert, AuditAction action, String channel, String actor, String failure) {

        // The badge says what happened and the aside says through what, so the summary is
        // the alert itself rather than a restatement of both.
        String summary = action == AuditAction.ALERT_SENT
                ? alert.summary()
                : "%s - delivery failed: %s".formatted(alert.summary(), failure);
        return AuditEvent.about(
                        new AuditEvent.SubjectRef(
                                alert.ownerType(), alert.ownerId(), alert.ownerDn(), alert.ownerName()),
                        action,
                        truncate(summary, 1000),
                        alert.raisedAt())
                .by(actor)
                .through(channel)
                .to(truncate(alert.contact(), 320))
                .forCertificate(alert.certificateFingerprint());
    }

    /** The columns are bounded; a channel's exception, or a long list of contacts, is not. */
    private static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit - 3) + "...";
    }
}

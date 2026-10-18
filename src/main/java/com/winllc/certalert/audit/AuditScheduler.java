package com.winllc.certalert.audit;

import com.winllc.certalert.cluster.JobLocks;
import com.winllc.certalert.license.Licensing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The audit trail's own housekeeping, independent of the directory jobs and of the license
 * (the audit trail is not a licensed feature):
 *
 * <ul>
 *   <li>retention pruning, daily ({@code cert-alert.audit.retention.cron});
 *   <li>writing collapsed denials and certificate sign-in failures whose minute is up;
 *   <li>checking the license mode once a minute, so a license that expires overnight is
 *       recorded as a mode change even if nobody is using the application.
 * </ul>
 *
 * <p>With more than one replica, retention runs on one of them at a time (see
 * {@link JobLocks}). The other two are each replica's own: the failures and denials it
 * collapses are the ones it saw, and the license mode it watches is the one it enforces, so
 * a mode change is recorded once by each replica that notices it.
 */
@Component
@EnableScheduling
public class AuditScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuditScheduler.class);

    private final EnsAuditProperties properties;
    private final AuditRetention retention;
    private final AccessDenials denials;
    private final AuthenticationAudit authentication;
    private final Licensing licensing;
    private final JobLocks locks;

    public AuditScheduler(
            EnsAuditProperties properties,
            AuditRetention retention,
            AccessDenials denials,
            AuthenticationAudit authentication,
            Licensing licensing,
            JobLocks locks) {
        this.properties = properties;
        this.retention = retention;
        this.denials = denials;
        this.authentication = authentication;
        this.licensing = licensing;
        this.locks = locks;
    }

    @Scheduled(cron = "${cert-alert.audit.retention.cron}", zone = "UTC")
    public void prune() {
        try {
            locks.runExclusively("audit-retention", (Runnable) retention::prune);
        } catch (RuntimeException e) {
            log.error("Pruning the audit trail failed", e);
        }
    }

    @Scheduled(fixedDelayString = "PT5S", initialDelayString = "PT5S")
    public void flushCollapsed() {
        if (!properties.isBackground()) {
            return;
        }
        denials.flushExpired();
        authentication.flushExpired();
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void watchLicenseMode() {
        if (!properties.isBackground()) {
            return;
        }
        licensing.current();
    }
}

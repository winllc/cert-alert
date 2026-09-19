package com.winllc.certalert.scheduling;

import com.winllc.certalert.domain.SyncJob;
import com.winllc.certalert.ldap.LdapProperties;
import com.winllc.certalert.config.NotificationProperties;
import com.winllc.certalert.service.AuditService;
import com.winllc.certalert.service.CertificateRefreshService;
import com.winllc.certalert.service.DirectoryPruneService;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.service.NotificationService;
import com.winllc.certalert.service.RevocationService;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled jobs that keep the cache in step with the directory.
 *
 * <p>Several separate schedules rather than one, because they are different kinds of work:
 *
 * <ul>
 *   <li><b>users</b> and <b>servers</b> scrape LDAP. They are staggered so two long sweeps
 *       never run at once, and either can be re-timed without disturbing the other.
 *   <li><b>refresh</b> re-evaluates cached expiry and touches no LDAP at all, so it is
 *       cheap enough to run hourly and keeps alerts and the tables current between sweeps.
 *   <li><b>revocation</b> asks the issuing authorities which of the cached certificates
 *       they have revoked - the one question about a certificate that the certificate
 *       cannot answer. It reads no LDAP and runs after both sweeps.
 *   <li><b>prune</b> removes entries the directory has stopped publishing. It is off by
 *       default: deleting records is not something to start doing on its own.
 *   <li><b>audit retention</b> trims the audit trail, and is off by default for the same
 *       reason - more so, since the whole point of that table is that it remembers.
 *   <li><b>expiry digest</b> tells each point of contact what of theirs is expiring, once a
 *       day. The alert channels tell the operators as things happen; this tells the person
 *       who has to renew it.
 * </ul>
 *
 * <p>Every job is guarded against overlapping itself. A sweep of a directory with 100,000+
 * entries can outlast its own interval, and two concurrent sweeps would fight over the
 * same rows to no benefit. A job that finds itself already running logs and stands down.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "cert-alert.ldap.sync", name = "enabled", matchIfMissing = true)
public class DirectoryJobScheduler {

    private static final Logger log = LoggerFactory.getLogger(DirectoryJobScheduler.class);

    private final DirectorySyncService syncService;
    private final CertificateRefreshService refreshService;
    private final DirectoryPruneService pruneService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final NotificationProperties notificationProperties;
    private final RevocationService revocationService;
    private final LdapProperties properties;

    private final Map<SyncJob, AtomicBoolean> running = new EnumMap<>(SyncJob.class);

    public DirectoryJobScheduler(
            DirectorySyncService syncService,
            CertificateRefreshService refreshService,
            DirectoryPruneService pruneService,
            AuditService auditService,
            NotificationService notificationService,
            NotificationProperties notificationProperties,
            RevocationService revocationService,
            LdapProperties properties) {
        this.syncService = syncService;
        this.refreshService = refreshService;
        this.pruneService = pruneService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.notificationProperties = notificationProperties;
        this.revocationService = revocationService;
        this.properties = properties;
        for (SyncJob job : SyncJob.values()) {
            running.put(job, new AtomicBoolean(false));
        }
    }

    @Scheduled(cron = "${cert-alert.ldap.sync.users-cron}", zone = "UTC")
    public void syncUsers() {
        runOnce(SyncJob.USERS, syncService::syncUsers);
    }

    @Scheduled(cron = "${cert-alert.ldap.sync.servers-cron}", zone = "UTC")
    public void syncServers() {
        runOnce(SyncJob.SERVERS, syncService::syncServers);
    }

    @Scheduled(cron = "${cert-alert.ldap.sync.refresh-cron}", zone = "UTC")
    public void refreshExpiry() {
        runOnce(SyncJob.REFRESH, refreshService::refresh);
    }

    /**
     * The daily round-up of what is expiring, to the people who are the contacts for it.
     * Separate from the alert channels, which tell a fixed list of operators as things
     * happen; this is the message somebody can act on, and it goes to them.
     */
    @Scheduled(cron = "${cert-alert.notifications.digest.cron}", zone = "UTC")
    public void expiryDigest() {
        if (!notificationProperties.getDigest().isEnabled()) {
            return;
        }
        try {
            notificationService.digest();
            notificationService.trim();
        } catch (RuntimeException e) {
            log.error("The expiry digest failed", e);
        }
    }

    /**
     * Trimming the audit trail. Not guarded like the others: it is one delete statement,
     * and it is not recorded in the run log because the run log is about the directory.
     */
    @Scheduled(cron = "${cert-alert.audit.retention.cron}", zone = "UTC")
    public void trimAuditTrail() {
        try {
            auditService.trim();
        } catch (RuntimeException e) {
            log.error("Trimming the audit trail failed", e);
        }
    }

    /**
     * Asking the issuing authorities what they have revoked. After both sweeps, because it
     * is a question about a current cache, and on its own schedule because it is work
     * against a different system entirely - the certificates' own authorities rather than
     * the directory.
     */
    @Scheduled(cron = "${cert-alert.revocation.cron}", zone = "UTC")
    public void checkRevocation() {
        runOnce(SyncJob.REVOCATION, revocationService::checkAll);
    }

    @Scheduled(cron = "${cert-alert.ldap.prune.cron}", zone = "UTC")
    public void prune() {
        if (!properties.getPrune().isEnabled()) {
            return;
        }
        runOnce(SyncJob.PRUNE, pruneService::prune);
    }

    /** Runs the job unless it is already running, and never lets it escape as an exception. */
    private void runOnce(SyncJob job, Runnable work) {
        AtomicBoolean guard = running.get(job);
        if (!guard.compareAndSet(false, true)) {
            log.warn("Skipping the scheduled {} job: the previous run has not finished", job);
            return;
        }
        try {
            work.run();
        } catch (RuntimeException e) {
            // Already logged with context by the service; swallowing it here keeps the
            // scheduler alive for the next firing.
            log.error("Scheduled {} job failed", job, e);
        } finally {
            guard.set(false);
        }
    }
}

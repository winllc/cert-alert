package com.winllc.certalert.scheduling;

import com.winllc.certalert.domain.SyncJob;
import com.winllc.certalert.ldap.LdapProperties;
import com.winllc.certalert.config.NotificationProperties;
import com.winllc.certalert.audit.JobAudit;
import com.winllc.certalert.license.Licensing;
import com.winllc.certalert.service.CertificateCleanupService;
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
 *   <li><b>certificate cleanup</b> deletes from the directory the certificates it should
 *       not still be publishing - revoked ones above all. The only job here that writes to
 *       the directory, and off by default for that reason.
 *   <li><b>prune</b> removes entries the directory has stopped publishing. It is off by
 *       default: deleting records is not something to start doing on its own.
 *   <li><b>audit retention</b> is not here: the audit trail is not a licensed feature, and
 *       prunes on its own schedule (see {@code AuditScheduler}).
 *   <li><b>expiry digest</b> tells each point of contact what of theirs is expiring, once a
 *       day. The alert channels tell the operators as things happen; this tells the person
 *       who has to renew it.
 * </ul>
 *
 * <p>Every job is guarded against overlapping itself. A sweep of a directory with 100,000+
 * entries can outlast its own interval, and two concurrent sweeps would fight over the
 * same rows to no benefit. A job that finds itself already running logs and stands down.
 *
 * <p>Every job is licensed (see {@link Licensing#mayRun}): the sweeps and the refresh are
 * {@code directory-sync}, revocation {@code revocation-checks}, certificate cleanup and
 * prune {@code directory-cleanup}, the expiry digest {@code notifications}. While the
 * license is not in full effect - none installed, expired, or a release it does not cover -
 * every job pauses, the housekeeping ones included. Their schedules stay wired either way; a
 * firing the license does not allow returns without doing anything, so installing a license
 * needs no restart.
 *
 * <p>Every run is recorded in the audit trail as {@code job.completed} or {@code job.failed},
 * with its counts and duration - one event per run, not per entry.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "cert-alert.ldap.sync", name = "enabled", matchIfMissing = true)
public class DirectoryJobScheduler {

    private static final Logger log = LoggerFactory.getLogger(DirectoryJobScheduler.class);

    private final DirectorySyncService syncService;
    private final CertificateRefreshService refreshService;
    private final DirectoryPruneService pruneService;
    private final JobAudit jobs;
    private final NotificationService notificationService;
    private final NotificationProperties notificationProperties;
    private final RevocationService revocationService;
    private final CertificateCleanupService cleanupService;
    private final LdapProperties properties;
    private final Licensing licensing;

    private final Map<SyncJob, AtomicBoolean> running = new EnumMap<>(SyncJob.class);

    public DirectoryJobScheduler(
            DirectorySyncService syncService,
            CertificateRefreshService refreshService,
            DirectoryPruneService pruneService,
            JobAudit jobs,
            NotificationService notificationService,
            NotificationProperties notificationProperties,
            RevocationService revocationService,
            CertificateCleanupService cleanupService,
            LdapProperties properties,
            Licensing licensing) {
        this.syncService = syncService;
        this.refreshService = refreshService;
        this.pruneService = pruneService;
        this.jobs = jobs;
        this.notificationService = notificationService;
        this.notificationProperties = notificationProperties;
        this.revocationService = revocationService;
        this.cleanupService = cleanupService;
        this.properties = properties;
        this.licensing = licensing;
        for (SyncJob job : SyncJob.values()) {
            running.put(job, new AtomicBoolean(false));
        }
    }

    @Scheduled(cron = "${cert-alert.ldap.sync.users-cron}", zone = "UTC")
    public void syncUsers() {
        if (!licensing.mayRun("directory-sync", "user sweep")) {
            return;
        }
        runOnce(SyncJob.USERS, "user sweep", syncService::syncUsers);
    }

    @Scheduled(cron = "${cert-alert.ldap.sync.servers-cron}", zone = "UTC")
    public void syncServers() {
        if (!licensing.mayRun("directory-sync", "server sweep")) {
            return;
        }
        runOnce(SyncJob.SERVERS, "server sweep", syncService::syncServers);
    }

    @Scheduled(cron = "${cert-alert.ldap.sync.refresh-cron}", zone = "UTC")
    public void refreshExpiry() {
        if (!licensing.mayRun("directory-sync", "expiry refresh")) {
            return;
        }
        runOnce(SyncJob.REFRESH, "expiry refresh", refreshService::refresh);
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
            if (licensing.mayRun("notifications", "expiry digest")) {
                jobs.run("expiry digest", notificationService::digest);
            }
            // Trimming old notifications is housekeeping for the in-app ones, which stay
            // whatever the license includes - but nothing runs while it is not in full effect.
            if (licensing.mayRun("notification trim")) {
                jobs.run("notification trim", notificationService::trim, removed -> java.util.Map.of("removed", removed));
            }
        } catch (RuntimeException e) {
            log.error("The expiry digest failed", e);
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
        if (!licensing.mayRun("revocation-checks", "revocation")) {
            return;
        }
        runOnce(SyncJob.REVOCATION, "revocation", revocationService::checkAll);
    }

    /**
     * Removing from the directory the certificates it should not still be publishing. Off
     * by default and checked here as well as in the service, so that a deployment which has
     * not turned it on does no work at all rather than doing the work and finding nothing.
     */
    @Scheduled(cron = "${cert-alert.ldap.certificate-cleanup.cron}", zone = "UTC")
    public void cleanUpCertificates() {
        if (!properties.getCertificateCleanup().isEnabled()
                || !licensing.mayRun("directory-cleanup", "certificate cleanup")) {
            return;
        }
        runOnce(SyncJob.CERTIFICATE_CLEANUP, "certificate cleanup", cleanupService::run);
    }

    @Scheduled(cron = "${cert-alert.ldap.prune.cron}", zone = "UTC")
    public void prune() {
        if (!properties.getPrune().isEnabled() || !licensing.mayRun("directory-cleanup", "prune")) {
            return;
        }
        runOnce(SyncJob.PRUNE, "prune", pruneService::prune);
    }

    /**
     * Runs the job unless it is already running, and never lets it escape as an exception.
     * The run is audited as job.completed or job.failed.
     */
    private void runOnce(SyncJob job, String name, java.util.function.Supplier<?> work) {
        AtomicBoolean guard = running.get(job);
        if (!guard.compareAndSet(false, true)) {
            log.warn("Skipping the scheduled {} job: the previous run has not finished", job);
            return;
        }
        try {
            jobs.run(name, work);
        } catch (RuntimeException e) {
            // Already logged with context by the service; swallowing it here keeps the
            // scheduler alive for the next firing.
            log.error("Scheduled {} job failed", job, e);
        } finally {
            guard.set(false);
        }
    }
}

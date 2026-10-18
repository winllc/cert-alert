package com.winllc.certalert.web;

import com.winllc.certalert.cluster.JobRunController;
import com.winllc.certalert.cluster.JobRunView;
import com.winllc.certalert.cluster.JobRuns;
import com.winllc.certalert.domain.SyncJob;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.license.Licensing;
import com.winllc.certalert.license.RequiresFeature;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.SyncRunRepository;
import com.winllc.certalert.ldap.ChangelogProperties;
import com.winllc.certalert.service.CertificateRefreshService;
import com.winllc.certalert.service.ChangelogConnector;
import com.winllc.certalert.service.ChangelogCursorStore;
import com.winllc.certalert.service.CertificateCleanupService;
import com.winllc.certalert.service.DirectoryPruneService;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.service.ResourceNotFoundException;
import com.winllc.certalert.web.dto.CachedCertificateRow;
import com.winllc.certalert.web.dto.ChangelogStatus;
import com.winllc.certalert.web.dto.DirectoryStats;
import com.winllc.certalert.web.dto.SyncResponse;
import com.winllc.certalert.web.dto.SyncRunRow;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual triggers for the scheduled jobs, the run log, and the cached certificate detail
 * behind a table row.
 *
 * <p>A job started here answers 202 at once with a run to ask after ({@code /api/v1/jobs/{id}},
 * see {@link JobRuns}) and runs in the background, under the same lock as its schedule: one
 * started while the job is already running, on any replica, is skipped rather than run twice.
 * The run's result, once it has one, is what these endpoints used to answer with.
 */
@RestController
@RequestMapping("/api/v1")
public class DirectoryApiController {

    private final JobRuns runs;

    private static final int MAX_RUN_HISTORY = 100;

    private final DirectorySyncService syncService;
    private final CertificateRefreshService refreshService;
    private final DirectoryPruneService pruneService;
    private final CertificateCleanupService cleanupService;
    private final DirectoryUserRepository userRepository;
    private final DirectoryServerRepository serverRepository;
    private final SyncRunRepository syncRunRepository;
    private final ChangelogCursorStore cursorStore;
    private final ChangelogProperties changelogProperties;
    // Absent unless the changelog connector is enabled.
    private final Optional<ChangelogConnector> connector;
    private final Licensing licensing;

    public DirectoryApiController(
            DirectorySyncService syncService,
            CertificateRefreshService refreshService,
            DirectoryPruneService pruneService,
            CertificateCleanupService cleanupService,
            DirectoryUserRepository userRepository,
            DirectoryServerRepository serverRepository,
            SyncRunRepository syncRunRepository,
            ChangelogCursorStore cursorStore,
            ChangelogProperties changelogProperties,
            Optional<ChangelogConnector> connector,
            Licensing licensing,
            JobRuns runs) {
        this.runs = runs;
        this.syncService = syncService;
        this.refreshService = refreshService;
        this.pruneService = pruneService;
        this.cleanupService = cleanupService;
        this.userRepository = userRepository;
        this.serverRepository = serverRepository;
        this.syncRunRepository = syncRunRepository;
        this.cursorStore = cursorStore;
        this.changelogProperties = changelogProperties;
        this.connector = connector;
        this.licensing = licensing;
    }

    /** Scrapes IC Persons now, rather than waiting for the schedule. */
    @RequiresFeature("directory-sync")
    @PostMapping("/sync/users")
    public ResponseEntity<JobRunView> syncUsers() {
        return JobRunController.accepted(runs, "user sweep", step -> sweepUsers(step));
    }

    /** Scrapes IC Non-Person Entities now. */
    @RequiresFeature("directory-sync")
    @PostMapping("/sync/servers")
    public ResponseEntity<JobRunView> syncServers() {
        return JobRunController.accepted(runs, "server sweep", step -> sweepServers(step));
    }

    /** Runs both sweeps, in the order the schedule would. */
    @RequiresFeature("directory-sync")
    @PostMapping("/sync")
    public ResponseEntity<JobRunView> sync() {
        return JobRunController.accepted(runs, "directory sync", step -> List.of(sweepUsers(step), sweepServers(step)));
    }

    private SyncResponse sweepUsers(JobRuns.Step step) {
        return SyncResponse.from(step.exclusive(SyncJob.USERS.lockName(), "user sweep", syncService::syncUsers));
    }

    private SyncResponse sweepServers(JobRuns.Step step) {
        return SyncResponse.from(step.exclusive(SyncJob.SERVERS.lockName(), "server sweep", syncService::syncServers));
    }

    /** Re-evaluates cached expiry without reading the directory. */
    @RequiresFeature("directory-sync")
    @PostMapping("/sync/refresh")
    public ResponseEntity<JobRunView> refresh() {
        return JobRunController.accepted(runs, "expiry refresh", step -> SyncResponse.from(
                step.exclusive(SyncJob.REFRESH.lockName(), "expiry refresh", refreshService::refresh)));
    }

    /** Removes entries the directory has stopped publishing. */
    @RequiresFeature("directory-cleanup")
    @PostMapping("/sync/prune")
    public ResponseEntity<JobRunView> prune() {
        return JobRunController.accepted(runs, "prune", step -> SyncResponse.from(
                step.exclusive(SyncJob.PRUNE.lockName(), "prune", pruneService::prune)));
    }

    /** How many entries the next prune would remove, for checking before enabling it. */
    @RequiresFeature("directory-cleanup")
    @GetMapping("/sync/prune/preview")
    public Map<String, Long> prunePreview() {
        return Map.of("prunable", pruneService.countPrunable());
    }

    /**
     * Deletes from the directory the certificates it should not still be publishing.
     *
     * <p>Does nothing at all unless {@code cert-alert.ldap.certificate-cleanup.enabled} is
     * set: running it by hand is bringing a scheduled job forward, not a way around the
     * switch that says this deployment permits writing to the directory.
     */
    @RequiresFeature("directory-cleanup")
    @PostMapping("/sync/certificate-cleanup")
    public ResponseEntity<JobRunView> cleanUpCertificates() {
        return JobRunController.accepted(runs, "certificate cleanup", step -> step.exclusive(
                SyncJob.CERTIFICATE_CLEANUP.lockName(), "certificate cleanup", cleanupService::run));
    }

    /** What that would remove, without removing it. */
    @RequiresFeature("directory-cleanup")
    @GetMapping("/sync/certificate-cleanup/preview")
    public CertificateCleanupService.Preview certificateCleanupPreview() {
        return cleanupService.preview();
    }

    /** The most recent runs, newest first. */
    @GetMapping("/sync/runs")
    @Transactional(readOnly = true)
    public List<SyncRunRow> runs(@RequestParam(defaultValue = "20") int limit) {
        return syncRunRepository.findByOrderByStartedAtDesc(PageRequest.of(0, Math.clamp(limit, 1, MAX_RUN_HISTORY)))
                .stream()
                .map(SyncRunRow::from)
                .toList();
    }

    /** Where the changelog connector has got to, and how far behind the directory it is. */
    @RequiresFeature("changelog-sync")
    @GetMapping("/changelog")
    public ChangelogStatus changelog() {
        // Read from configuration, not from whether a bean happens to exist: the cursor
        // store is always present, so its presence says nothing about the connector.
        if (!changelogProperties.isEnabled()) {
            return ChangelogStatus.notRunning(false);
        }
        boolean running = connector.map(ChangelogConnector::isRunning).orElse(false);
        return cursorStore.find()
                .map(cursor -> ChangelogStatus.from(cursor, running))
                .orElseGet(() -> ChangelogStatus.notRunning(true));
    }

    /**
     * Reads one batch from the changelog now, rather than waiting for the next poll.
     *
     * <p>Useful when the loop is deliberately not auto-started, and for confirming the
     * connector can reach the changelog at all without waiting out an interval.
     */
    @RequiresFeature("changelog-sync")
    @PostMapping("/changelog/poll")
    public ResponseEntity<JobRunView> pollChangelog() {
        return JobRunController.accepted(runs, "changelog sync", step -> {
            connector.ifPresent(c -> step.exclusive(
                    ChangelogConnector.LOCK, "changelog sync", c::pollOnce, applied -> Map.of("changes", applied)));
            return changelog();
        });
    }

    /** The roll-up behind the cards at the top of each search page. */
    @GetMapping("/stats/users")
    @Transactional(readOnly = true)
    public DirectoryStats userStats() {
        return DirectoryStats.from(userRepository.countByCertificateStatus());
    }

    @GetMapping("/stats/servers")
    @Transactional(readOnly = true)
    public DirectoryStats serverStats() {
        return DirectoryStats.from(serverRepository.countByCertificateStatus());
    }

    @GetMapping("/users/{id}/certificates")
    @Transactional(readOnly = true)
    public List<CachedCertificateRow> userCertificates(@PathVariable Long id) {
        DirectoryUser user =
                userRepository.findWithCertificatesById(id).orElseThrow(() -> ResourceNotFoundException.user(id));
        return rows(user.getCertificates());
    }

    @GetMapping("/servers/{id}/certificates")
    @Transactional(readOnly = true)
    public List<CachedCertificateRow> serverCertificates(@PathVariable Long id) {
        DirectoryServer server =
                serverRepository.findWithCertificatesById(id).orElseThrow(() -> ResourceNotFoundException.server(id));
        return rows(server.getCertificates());
    }

    /** Risk flags are shown only where risk analysis is licensed. */
    private List<CachedCertificateRow> rows(List<CachedCertificate> certificates) {
        boolean risks = licensing.shows("risky-names");
        return certificates.stream()
                .map(CachedCertificateRow::from)
                .map(row -> risks ? row : row.withoutRisks())
                .toList();
    }
}

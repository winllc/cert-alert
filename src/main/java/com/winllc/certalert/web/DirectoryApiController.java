package com.winllc.certalert.web;

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
 */
@RestController
@RequestMapping("/api/v1")
public class DirectoryApiController {

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
            Optional<ChangelogConnector> connector) {
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
    }

    /** Scrapes IC Persons now, rather than waiting for the schedule. */
    @PostMapping("/sync/users")
    public SyncResponse syncUsers() {
        return SyncResponse.from(syncService.syncUsers());
    }

    /** Scrapes IC Non-Person Entities now. */
    @PostMapping("/sync/servers")
    public SyncResponse syncServers() {
        return SyncResponse.from(syncService.syncServers());
    }

    /** Runs both sweeps, in the order the schedule would. */
    @PostMapping("/sync")
    public List<SyncResponse> sync() {
        return List.of(
                SyncResponse.from(syncService.syncUsers()), SyncResponse.from(syncService.syncServers()));
    }

    /** Re-evaluates cached expiry without reading the directory. */
    @PostMapping("/sync/refresh")
    public SyncResponse refresh() {
        return SyncResponse.from(refreshService.refresh());
    }

    /** Removes entries the directory has stopped publishing. */
    @PostMapping("/sync/prune")
    public SyncResponse prune() {
        return SyncResponse.from(pruneService.prune());
    }

    /** How many entries the next prune would remove, for checking before enabling it. */
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
    @PostMapping("/sync/certificate-cleanup")
    public CertificateCleanupService.Result cleanUpCertificates() {
        return cleanupService.run();
    }

    /** What that would remove, without removing it. */
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
    @PostMapping("/changelog/poll")
    public ChangelogStatus pollChangelog() {
        connector.ifPresent(ChangelogConnector::pollOnce);
        return changelog();
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
        return user.getCertificates().stream().map(CachedCertificateRow::from).toList();
    }

    @GetMapping("/servers/{id}/certificates")
    @Transactional(readOnly = true)
    public List<CachedCertificateRow> serverCertificates(@PathVariable Long id) {
        DirectoryServer server =
                serverRepository.findWithCertificatesById(id).orElseThrow(() -> ResourceNotFoundException.server(id));
        return server.getCertificates().stream().map(CachedCertificateRow::from).toList();
    }
}

package com.winllc.certalert.service;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.SyncJob;
import com.winllc.certalert.domain.SyncStatus;
import com.winllc.certalert.ldap.LdapProperties;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.PrunableEntry;
import com.winllc.certalert.repository.SyncRunRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes entries the directory has stopped publishing.
 *
 * <p>Deletion is driven by how long an entry has gone unseen rather than by comparing a
 * sweep's results against the database, because a sweep that dies halfway through would
 * otherwise look exactly like a directory that lost half its entries. With the window set
 * comfortably longer than the sync interval, several consecutive failed sweeps still
 * cannot delete anything.
 *
 * <p>Nor can a month of them. Each kind of entry is pruned only when a sweep of that kind
 * has completed with no errors since the newest of them was last seen - so every entry
 * deleted was missing from a whole, clean sweep - and only when they are no more than
 * {@code max-share} of that kind. Otherwise that kind is left alone, and the run says so.
 * What a prune deletes takes hand-entered contacts, aliases and project memberships with
 * it, which no later sweep can bring back.
 */
@Service
public class DirectoryPruneService {

    private static final Logger log = LoggerFactory.getLogger(DirectoryPruneService.class);

    private final DirectoryUserRepository userRepository;
    private final DirectoryServerRepository serverRepository;
    private final LdapProperties properties;
    private final SyncRunRecorder runRecorder;
    private final AuditService auditService;
    private final SyncRunRepository syncRuns;
    private final Clock clock;

    public DirectoryPruneService(
            DirectoryUserRepository userRepository,
            DirectoryServerRepository serverRepository,
            LdapProperties properties,
            SyncRunRecorder runRecorder,
            AuditService auditService,
            SyncRunRepository syncRuns,
            Clock clock) {
        this.userRepository = userRepository;
        this.serverRepository = serverRepository;
        this.properties = properties;
        this.runRecorder = runRecorder;
        this.auditService = auditService;
        this.syncRuns = syncRuns;
        this.clock = clock;
    }

    private List<AuditEvent> recordsFor(
            List<PrunableEntry> entries, OwnerType type, Instant cutoff, Instant now) {

        String actor = AuditActors.current(AuditActors.PRUNE);
        String summary = "Deleted: the directory had not published it since %s".formatted(cutoff);
        return entries.stream()
                .map(entry -> AuditEvent.about(
                                new AuditEvent.SubjectRef(type, entry.getId(), entry.getDn(), entry.getName()),
                                AuditAction.ENTRY_PRUNED,
                                summary,
                                now)
                        .by(actor))
                .toList();
    }

    /** Deletes entries unseen for longer than the configured window. */
    @Transactional
    public DirectorySyncResult prune() {
        Instant now = Instant.now(clock);
        long startedNanos = System.nanoTime();
        Instant cutoff = now.minus(properties.getPrune().getAfter());
        Long runId = runRecorder.started(SyncJob.PRUNE, now);

        int pruned;
        int refused;
        try {
            // Named before they are deleted: afterwards there is nothing left to name, and
            // the record of a deletion is the one an audit trail exists for.
            List<AuditEvent> records = new ArrayList<>();
            int users = 0;
            int servers = 0;
            String usersRefused = refusal(SyncJob.USERS, "user",
                    userRepository.countByLastSyncedAtBefore(cutoff), userRepository.count(),
                    () -> userRepository.findNewestSyncedBefore(cutoff));
            if (usersRefused == null) {
                records.addAll(recordsFor(userRepository.findPrunableBefore(cutoff), OwnerType.USER, cutoff, now));
                users = userRepository.deleteByLastSyncedAtBefore(cutoff);
            }
            String serversRefused = refusal(SyncJob.SERVERS, "server",
                    serverRepository.countByLastSyncedAtBefore(cutoff), serverRepository.count(),
                    () -> serverRepository.findNewestSyncedBefore(cutoff));
            if (serversRefused == null) {
                records.addAll(recordsFor(serverRepository.findPrunableBefore(cutoff), OwnerType.SERVER, cutoff, now));
                servers = serverRepository.deleteByLastSyncedAtBefore(cutoff);
            }
            pruned = users + servers;
            refused = (usersRefused == null ? 0 : 1) + (serversRefused == null ? 0 : 1);
            auditService.recordAll(records);
            log.info("Pruned {} user(s) and {} server(s) unseen since {}", users, servers, cutoff);
        } catch (RuntimeException e) {
            runRecorder.failed(runId, Instant.now(clock), e.toString());
            log.error("Prune failed", e);
            throw e;
        }

        // A kind left alone counts as an error, so the run reads COMPLETED_WITH_ERRORS.
        DirectorySyncResult result = new DirectorySyncResult(
                SyncJob.PRUNE, 0, 0, 0, 0, 0, pruned, refused, Duration.ofNanos(System.nanoTime() - startedNanos));
        runRecorder.finished(runId, Instant.now(clock), result);
        return result;
    }

    /**
     * Why entries of this kind may not be pruned now, or null where they may (or there are
     * none to prune). Logged as a warning, since it means somebody has a sweep to look at.
     */
    private String refusal(
            SyncJob sweep, String kind, long candidates, long total, Supplier<Instant> newestCandidate) {
        if (candidates == 0) {
            return null;
        }
        String reason = null;
        Instant newest = newestCandidate.get();
        double maxShare = properties.getPrune().getMaxShare();
        if (newest == null || !syncRuns.existsByJobAndStatusAndStartedAtAfter(sweep, SyncStatus.COMPLETED, newest)) {
            reason = "no %s sweep has completed without errors since they were last seen, so they may only have been missed"
                    .formatted(kind);
        } else if (candidates > total * maxShare) {
            reason = "that is more than %s of every %s (cert-alert.ldap.prune.max-share)"
                    .formatted(java.text.NumberFormat.getPercentInstance(java.util.Locale.ROOT).format(maxShare), kind);
        }
        if (reason != null) {
            log.warn("Prune: left {} unseen {} entr(ies) of {} in place: {}", candidates, kind, total, reason);
        }
        return reason;
    }

    /** How many entries the next prune would remove, for checking before enabling it. */
    @Transactional(readOnly = true)
    public long countPrunable() {
        Instant cutoff = Instant.now(clock).minus(properties.getPrune().getAfter());
        return userRepository.countByLastSyncedAtBefore(cutoff)
                + serverRepository.countByLastSyncedAtBefore(cutoff);
    }
}

package com.winllc.certalert.service;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.SyncJob;
import com.winllc.certalert.ldap.LdapProperties;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.PrunableEntry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
 */
@Service
public class DirectoryPruneService {

    private static final Logger log = LoggerFactory.getLogger(DirectoryPruneService.class);

    private final DirectoryUserRepository userRepository;
    private final DirectoryServerRepository serverRepository;
    private final LdapProperties properties;
    private final SyncRunRecorder runRecorder;
    private final AuditService auditService;
    private final Clock clock;

    public DirectoryPruneService(
            DirectoryUserRepository userRepository,
            DirectoryServerRepository serverRepository,
            LdapProperties properties,
            SyncRunRecorder runRecorder,
            AuditService auditService,
            Clock clock) {
        this.userRepository = userRepository;
        this.serverRepository = serverRepository;
        this.properties = properties;
        this.runRecorder = runRecorder;
        this.auditService = auditService;
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
        try {
            // Named before they are deleted: afterwards there is nothing left to name, and
            // the record of a deletion is the one an audit trail exists for.
            List<AuditEvent> records = new ArrayList<>();
            records.addAll(recordsFor(userRepository.findPrunableBefore(cutoff), OwnerType.USER, cutoff, now));
            records.addAll(recordsFor(serverRepository.findPrunableBefore(cutoff), OwnerType.SERVER, cutoff, now));

            int users = userRepository.deleteByLastSyncedAtBefore(cutoff);
            int servers = serverRepository.deleteByLastSyncedAtBefore(cutoff);
            pruned = users + servers;
            auditService.recordAll(records);
            log.info("Pruned {} user(s) and {} server(s) unseen since {}", users, servers, cutoff);
        } catch (RuntimeException e) {
            runRecorder.failed(runId, Instant.now(clock), e.toString());
            log.error("Prune failed", e);
            throw e;
        }

        DirectorySyncResult result = new DirectorySyncResult(
                SyncJob.PRUNE, 0, 0, 0, 0, 0, pruned, 0, Duration.ofNanos(System.nanoTime() - startedNanos));
        runRecorder.finished(runId, Instant.now(clock), result);
        return result;
    }

    /** How many entries the next prune would remove, for checking before enabling it. */
    @Transactional(readOnly = true)
    public long countPrunable() {
        Instant cutoff = Instant.now(clock).minus(properties.getPrune().getAfter());
        return userRepository.countByLastSyncedAtBefore(cutoff)
                + serverRepository.countByLastSyncedAtBefore(cutoff);
    }
}

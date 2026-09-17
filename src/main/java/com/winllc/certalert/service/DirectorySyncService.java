package com.winllc.certalert.service;

import com.winllc.certalert.domain.SyncJob;
import com.winllc.certalert.ldap.LdapDirectoryClient;
import com.winllc.certalert.ldap.LdapProperties;
import com.winllc.certalert.ldap.LdapServerEntry;
import com.winllc.certalert.ldap.LdapUserEntry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Scrapes the directory and hands entries to {@link DirectoryPersistenceService} in
 * batches.
 *
 * <p>Nothing is collected: entries arrive from the paged search one at a time, fill a
 * batch, and are written and discarded. Memory stays flat whether the directory holds a
 * thousand entries or several hundred thousand.
 *
 * <p>The LDAP read deliberately happens outside any transaction. Holding a database
 * connection open across a directory-wide network read is a good way to exhaust the pool.
 */
@Service
public class DirectorySyncService {

    private static final Logger log = LoggerFactory.getLogger(DirectorySyncService.class);

    private final LdapDirectoryClient directoryClient;
    private final DirectoryPersistenceService persistenceService;
    private final LdapProperties properties;
    private final SyncRunRecorder runRecorder;
    private final Clock clock;

    public DirectorySyncService(
            LdapDirectoryClient directoryClient,
            DirectoryPersistenceService persistenceService,
            LdapProperties properties,
            SyncRunRecorder runRecorder,
            Clock clock) {
        this.directoryClient = directoryClient;
        this.persistenceService = persistenceService;
        this.properties = properties;
        this.runRecorder = runRecorder;
        this.clock = clock;
    }

    /** Scrapes every IC Person. */
    public DirectorySyncResult syncUsers() {
        return run(SyncJob.USERS, (collector, now) ->
                directoryClient.forEachUser(entry -> collector.accept(entry)));
    }

    /** Scrapes every IC Non-Person Entity. */
    public DirectorySyncResult syncServers() {
        return run(SyncJob.SERVERS, (collector, now) ->
                directoryClient.forEachServer(entry -> collector.accept(entry)));
    }

    private <T> DirectorySyncResult run(SyncJob job, BiFunction<BatchCollector<T>, Instant, Integer> scrape) {
        Instant startedAt = Instant.now(clock);
        long startedNanos = System.nanoTime();
        Long runId = runRecorder.started(job, startedAt);
        log.info("{} sync starting", job);

        BatchCollector<T> collector = new BatchCollector<>(job, startedAt);
        int seen;
        try {
            seen = scrape.apply(collector, startedAt);
            collector.flush();
        } catch (RuntimeException e) {
            runRecorder.failed(runId, Instant.now(clock), e.toString());
            log.error("{} sync failed", job, e);
            throw e;
        }

        DirectorySyncResult result = new DirectorySyncResult(
                job,
                seen,
                collector.outcome.created(),
                collector.outcome.certificatesCached(),
                collector.outcome.certificatesRemoved(),
                collector.outcome.alertsRaised(),
                0,
                collector.errors,
                Duration.ofNanos(System.nanoTime() - startedNanos));

        runRecorder.finished(runId, Instant.now(clock), result);
        log.info(
                "{} sync finished in {} ms: {} entries ({} new), {} certificate(s) cached, {} removed, "
                        + "{} alert(s), {} failed batch(es)",
                job,
                result.duration().toMillis(),
                result.entriesSeen(),
                result.entriesCreated(),
                result.certificatesCached(),
                result.certificatesRemoved(),
                result.alertsRaised(),
                result.errors());
        return result;
    }

    /**
     * Fills a batch, writes it, and forgets it. A batch that blows up is logged and
     * skipped: on a directory this size, one malformed entry must not cost the sweep.
     */
    private final class BatchCollector<T> {

        private final SyncJob job;
        private final Instant now;
        private final List<T> batch;
        // Resolved once, at the start: a sweep somebody triggered is theirs, and a
        // scheduled one is the job's. The security context does not reach the batch.
        private final String actor = AuditActors.current(AuditActors.SYNC);
        private BatchOutcome outcome = BatchOutcome.EMPTY;
        private int errors;
        private int written;

        private BatchCollector(SyncJob job, Instant now) {
            this.job = job;
            this.now = now;
            this.batch = new ArrayList<>(properties.getBatchSize());
        }

        private void accept(T entry) {
            batch.add(entry);
            if (batch.size() >= properties.getBatchSize()) {
                flush();
            }
        }

        @SuppressWarnings("unchecked")
        private void flush() {
            if (batch.isEmpty()) {
                return;
            }
            List<T> pending = List.copyOf(batch);
            batch.clear();
            try {
                outcome = outcome.plus(job == SyncJob.USERS
                        ? persistenceService.upsertUsers((List<LdapUserEntry>) pending, now, actor)
                        : persistenceService.upsertServers((List<LdapServerEntry>) pending, now, actor));
            } catch (RuntimeException e) {
                errors++;
                log.error("Failed to write a batch of {} {} entries", pending.size(), job, e);
            }
            written += pending.size();
            if (written % 10_000 < properties.getBatchSize()) {
                log.info("{} sync progress: {} entries written", job, written);
            }
        }
    }
}

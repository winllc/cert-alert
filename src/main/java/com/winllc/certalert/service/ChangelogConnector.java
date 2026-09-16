package com.winllc.certalert.service;

import com.winllc.certalert.domain.ChangelogCursor;
import com.winllc.certalert.ldap.ChangelogBounds;
import com.winllc.certalert.ldap.ChangelogEntry;
import com.winllc.certalert.ldap.ChangelogProperties;
import com.winllc.certalert.ldap.LdapChangelogClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

/**
 * Follows the directory's changelog continuously, keeping the cache current between sweeps.
 *
 * <p>The scheduled sweeps read the whole tree; on a directory of 100,000+ entries that is
 * something you do nightly, not something you do to find out that somebody's certificate was
 * replaced twenty minutes ago. This connector closes that window: it reads the changelog
 * from where it last got to, applies each change, and records its new position.
 *
 * <p>Its position lives in the database, so a restart resumes rather than replaying the
 * directory's history or skipping whatever happened while it was down. Three things it takes
 * care to get right:
 *
 * <ul>
 *   <li><b>Only moving forward.</b> The cursor advances one change at a time and is written
 *       after each batch, so a crash mid-batch re-applies at most that batch - and applying
 *       a change is idempotent.
 *   <li><b>Noticing a gap.</b> A changelog is trimmed as it ages. If the directory has
 *       discarded changes this connector never reached, the cache is missing them, and no
 *       amount of further reading will find them. That triggers a full sweep rather than
 *       carrying on quietly wrong.
 *   <li><b>Backing off.</b> A directory that is down should not be hammered once per poll
 *       interval; the wait grows to a configured ceiling and resets on the first success.
 * </ul>
 *
 * <p>This runs in every instance that starts it. Running more than one against the same
 * database would have them apply the same changes twice - harmless, since applying is
 * idempotent, but wasteful; see the README on leader election.
 */
@Service
@ConditionalOnProperty(prefix = "cert-alert.ldap.changelog", name = "enabled", havingValue = "true")
public class ChangelogConnector implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ChangelogConnector.class);

    private final LdapChangelogClient changelogClient;
    private final DirectoryChangeApplier applier;
    private final ChangelogCursorStore cursorStore;
    private final DirectorySyncService syncService;
    private final ChangelogProperties properties;
    private final Clock clock;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread worker;

    public ChangelogConnector(
            LdapChangelogClient changelogClient,
            DirectoryChangeApplier applier,
            ChangelogCursorStore cursorStore,
            DirectorySyncService syncService,
            ChangelogProperties properties,
            Clock clock) {
        this.changelogClient = changelogClient;
        this.applier = applier;
        this.cursorStore = cursorStore;
        this.syncService = syncService;
        this.properties = properties;
        this.clock = clock;
    }

    // --- lifecycle ---------------------------------------------------------------------

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(this::run, "changelog-connector");
        thread.setDaemon(true);
        this.worker = thread;
        thread.start();
        log.info("Changelog connector started, following '{}'", properties.getBaseDn());
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Thread thread = this.worker;
        if (thread != null) {
            // It spends most of its life asleep between polls; interrupting cuts that short
            // rather than making shutdown wait out a poll interval.
            thread.interrupt();
            try {
                thread.join(Duration.ofSeconds(10).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("Changelog connector stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return properties.isAutoStart();
    }

    /** Late, so the schema and the rest of the application are ready before it reads. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1000;
    }

    // --- the loop ----------------------------------------------------------------------

    private void run() {
        Duration backoff = Duration.ZERO;
        while (running.get()) {
            try {
                int applied = pollOnce();
                backoff = Duration.ZERO;
                // Something was there: come straight back rather than sleeping, so a burst
                // of changes drains at the speed of the directory rather than the clock.
                if (!sleep(applied > 0 ? Duration.ZERO : properties.getPollInterval())) {
                    return;
                }
            } catch (RuntimeException e) {
                backoff = nextBackoff(backoff);
                recordError(e);
                log.error("Changelog poll failed; retrying in {}s", backoff.toSeconds(), e);
                if (!sleep(backoff)) {
                    return;
                }
            }
        }
    }

    /**
     * Reads and applies one batch.
     *
     * <p>Public so a poll can be driven by hand - by an operator through the API, or by a
     * test - as well as by the loop.
     *
     * @return how many changes were read, so the caller can decide whether to wait
     */
    public int pollOnce() {
        Instant now = Instant.now(clock);
        ChangelogBounds bounds = changelogClient.readBounds();
        ChangelogCursor cursor = cursorStore.loadOrCreate(startingPoint(bounds), now);

        if (hasGap(cursor, bounds)) {
            handleGap(cursor, bounds, now);
            return 0;
        }

        List<ChangelogEntry> changes = changelogClient.readChanges(cursor.getLastChangeNumber(),
                properties.getBatchSize());
        cursorStore.recordPoll(cursor.getId(), now, bounds);
        if (changes.isEmpty()) {
            return 0;
        }

        log.debug("Applying {} change(s) from {}", changes.size(), cursor.getLastChangeNumber() + 1);
        for (ChangelogEntry change : changes) {
            // Shutdown interrupts the worker; that, rather than the lifecycle flag, is what
            // cuts a batch short. Keying this off the flag would mean a poll triggered by
            // hand - with the loop not running - read its changes and applied none of them.
            if (Thread.currentThread().isInterrupted()) {
                log.info("Stopping mid-batch at change {}", change.changeNumber());
                break;
            }
            applyAndAdvance(cursor.getId(), change, now);
        }
        return changes.size();
    }

    /** One change, then its position, so the cursor never runs ahead of the work. */
    private void applyAndAdvance(Long cursorId, ChangelogEntry change, Instant now) {
        DirectoryChangeApplier.Outcome outcome;
        try {
            outcome = applier.apply(change, now);
        } catch (RuntimeException e) {
            // Step over a change that cannot be applied rather than wedging on it; the
            // nightly sweep is the backstop, and the error is counted and surfaced.
            recordError(e);
            log.error("Could not apply change {} at '{}'; skipping it", change.changeNumber(), change.targetDn(), e);
            outcome = DirectoryChangeApplier.Outcome.IGNORED;
        }
        cursorStore.advance(cursorId, change.changeNumber(), now, outcome == DirectoryChangeApplier.Outcome.APPLIED);
    }

    // --- gap handling ------------------------------------------------------------------

    /** True when the directory has discarded changes this cursor had not reached. */
    private boolean hasGap(ChangelogCursor cursor, ChangelogBounds bounds) {
        return bounds.first() != null
                && cursor.getLastChangeNumber() > 0
                && cursor.getLastChangeNumber() < bounds.first() - 1;
    }

    private void handleGap(ChangelogCursor cursor, ChangelogBounds bounds, Instant now) {
        log.error(
                "Changelog gap: the cache is at change {} but the directory now keeps only {} onwards. "
                        + "Changes in between are lost to this connector.",
                cursor.getLastChangeNumber(),
                bounds.first());
        cursorStore.recordGap(cursor.getId(), bounds.first());

        if (properties.isFullSyncOnGap()) {
            // The only way back to a correct cache is to read the tree again.
            log.warn("Running a full sweep to recover from the changelog gap");
            syncService.syncUsers();
            syncService.syncServers();
        } else {
            log.warn("cert-alert.ldap.changelog.full-sync-on-gap is off; the cache may be stale until the next sweep");
        }
    }

    // --- cursor persistence ------------------------------------------------------------

    /**
     * Where to begin with no stored position.
     *
     * <p>From the latest change by default: the cache is populated by a full sweep, and
     * replaying the directory's entire retained history to arrive at the same state would be
     * work for nothing.
     */
    private long startingPoint(ChangelogBounds bounds) {
        if (properties.getStartFrom() == ChangelogProperties.StartPosition.BEGINNING) {
            return bounds.first() == null ? 0 : bounds.first() - 1;
        }
        return bounds.last() == null ? 0 : bounds.last();
    }

    private void recordError(Exception e) {
        cursorStore.recordError(e.toString());
    }

    // --- helpers -----------------------------------------------------------------------

    private Duration nextBackoff(Duration current) {
        if (current.isZero()) {
            return properties.getPollInterval();
        }
        Duration doubled = current.multipliedBy(2);
        return doubled.compareTo(properties.getMaxBackoff()) > 0 ? properties.getMaxBackoff() : doubled;
    }

    /** @return false when interrupted, meaning the loop should end */
    private boolean sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return true;
        }
        try {
            Thread.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

package com.winllc.certalert.service;

import com.winllc.certalert.domain.ChangelogCursor;
import com.winllc.certalert.ldap.ChangelogBounds;
import com.winllc.certalert.repository.ChangelogCursorRepository;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the connector's position.
 *
 * <p>A bean of its own so the transactions are real: called from inside
 * {@link ChangelogConnector} these would be self-invocations, which no proxy intercepts.
 * Each write is its own transaction, so recording where the connector got to does not ride
 * on whatever else is in flight.
 */
@Service
public class ChangelogCursorStore {

    private static final Logger log = LoggerFactory.getLogger(ChangelogCursorStore.class);

    private final ChangelogCursorRepository repository;

    public ChangelogCursorStore(ChangelogCursorRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<ChangelogCursor> find() {
        return repository.findByName(ChangelogCursor.DEFAULT_NAME);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChangelogCursor loadOrCreate(long startingChangeNumber, Instant now) {
        return repository
                .findByName(ChangelogCursor.DEFAULT_NAME)
                .orElseGet(() -> {
                    log.info("Changelog connector starting from change {}", startingChangeNumber);
                    return repository.save(
                            new ChangelogCursor(ChangelogCursor.DEFAULT_NAME, startingChangeNumber, now));
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void advance(Long cursorId, long changeNumber, Instant at, boolean applied) {
        repository.findById(cursorId).ifPresent(cursor -> {
            cursor.advanceTo(changeNumber, at, applied);
            repository.save(cursor);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPoll(Long cursorId, Instant at, ChangelogBounds bounds) {
        repository.findById(cursorId).ifPresent(cursor -> {
            cursor.recordPoll(at, bounds.first(), bounds.last());
            repository.save(cursor);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordGap(Long cursorId, long firstAvailable) {
        repository.findById(cursorId).ifPresent(cursor -> {
            cursor.recordGap(firstAvailable);
            repository.save(cursor);
        });
    }

    /** Never throws: the database may be the very thing that is failing. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordError(String message) {
        try {
            repository.findByName(ChangelogCursor.DEFAULT_NAME).ifPresent(cursor -> {
                cursor.recordError(message);
                repository.save(cursor);
            });
        } catch (RuntimeException e) {
            log.debug("Could not record the changelog error", e);
        }
    }
}

package com.winllc.certalert.service;

import com.winllc.certalert.domain.SyncJob;
import com.winllc.certalert.domain.SyncRun;
import com.winllc.certalert.repository.SyncRunRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the run log.
 *
 * <p>Each write is its own transaction, so the record of a run that failed survives
 * whatever went wrong inside it. Failing to write the log never fails the job - a lost
 * audit row is not worth abandoning a completed sweep over.
 */
@Service
public class SyncRunRecorder {

    private static final Logger log = LoggerFactory.getLogger(SyncRunRecorder.class);

    private final SyncRunRepository repository;

    public SyncRunRecorder(SyncRunRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long started(SyncJob job, Instant startedAt) {
        try {
            return repository.save(SyncRun.started(job, startedAt)).getId();
        } catch (RuntimeException e) {
            log.warn("Could not record the start of the {} run", job, e);
            return null;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finished(Long runId, Instant finishedAt, DirectorySyncResult result) {
        update(runId, run -> {
            run.record(
                    result.entriesSeen(),
                    result.entriesCreated(),
                    result.certificatesCached(),
                    result.certificatesRemoved(),
                    result.alertsRaised(),
                    result.errors());
            run.recordPruned(result.entriesPruned());
            run.succeeded(finishedAt);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(Long runId, Instant finishedAt, String message) {
        update(runId, run -> run.failed(finishedAt, message));
    }

    private void update(Long runId, java.util.function.Consumer<SyncRun> change) {
        if (runId == null) {
            return;
        }
        try {
            repository.findById(runId).ifPresent(run -> {
                change.accept(run);
                repository.save(run);
            });
        } catch (RuntimeException e) {
            log.warn("Could not update run {}", runId, e);
        }
    }
}

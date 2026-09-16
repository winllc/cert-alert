package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.SyncJob;
import com.winllc.certalert.domain.SyncRun;
import com.winllc.certalert.domain.SyncStatus;
import java.time.Instant;

/** One entry of the run log. */
public record SyncRunRow(
        Long id,
        SyncJob job,
        SyncStatus status,
        Instant startedAt,
        Instant finishedAt,
        long durationMillis,
        int entriesSeen,
        int entriesCreated,
        int certificatesCached,
        int certificatesRemoved,
        int alertsRaised,
        int entriesPruned,
        int errors,
        String message) {

    public static SyncRunRow from(SyncRun run) {
        return new SyncRunRow(
                run.getId(),
                run.getJob(),
                run.getStatus(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.duration().toMillis(),
                run.getEntriesSeen(),
                run.getEntriesCreated(),
                run.getCertificatesCached(),
                run.getCertificatesRemoved(),
                run.getAlertsRaised(),
                run.getEntriesPruned(),
                run.getErrors(),
                run.getMessage());
    }
}

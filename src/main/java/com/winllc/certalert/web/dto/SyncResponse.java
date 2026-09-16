package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.SyncJob;
import com.winllc.certalert.service.DirectorySyncResult;

/** Outcome of an on-demand run of one of the scheduled jobs. */
public record SyncResponse(
        SyncJob job,
        int entriesSeen,
        int entriesCreated,
        int certificatesCached,
        int certificatesRemoved,
        int alertsRaised,
        int entriesPruned,
        int errors,
        long durationMillis) {

    public static SyncResponse from(DirectorySyncResult result) {
        return new SyncResponse(
                result.job(),
                result.entriesSeen(),
                result.entriesCreated(),
                result.certificatesCached(),
                result.certificatesRemoved(),
                result.alertsRaised(),
                result.entriesPruned(),
                result.errors(),
                result.duration().toMillis());
    }
}

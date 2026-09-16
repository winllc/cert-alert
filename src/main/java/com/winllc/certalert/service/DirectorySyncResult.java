package com.winllc.certalert.service;

import com.winllc.certalert.domain.SyncJob;
import java.time.Duration;

/**
 * Summary of one run.
 *
 * @param job which job ran
 * @param entriesSeen entries read from the directory
 * @param entriesCreated entries seen for the first time
 * @param certificatesCached certificates newly cached
 * @param certificatesRemoved cached certificates the directory no longer publishes
 * @param alertsRaised certificates that moved into an alertable state
 * @param entriesPruned entries removed because the directory has stopped publishing them
 * @param errors batches that could not be processed
 * @param duration wall-clock time taken
 */
public record DirectorySyncResult(
        SyncJob job,
        int entriesSeen,
        int entriesCreated,
        int certificatesCached,
        int certificatesRemoved,
        int alertsRaised,
        int entriesPruned,
        int errors,
        Duration duration) {}

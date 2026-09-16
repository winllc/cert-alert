package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.ChangelogCursor;
import java.time.Instant;

/**
 * Where the changelog connector has got to.
 *
 * @param enabled whether the connector is configured to run at all
 * @param running whether its loop is alive
 * @param lastChangeNumber the last change it processed
 * @param firstAvailableNumber lowest change the directory still holds, where it says
 * @param lastAvailableNumber highest change the directory has recorded, where it says
 * @param lag how far behind the directory it is, where both are known
 * @param changesApplied changes that altered the cache
 * @param changesIgnored changes to entries this application does not track
 * @param gapsDetected times the directory discarded changes before they were read
 */
public record ChangelogStatus(
        boolean enabled,
        boolean running,
        Long lastChangeNumber,
        Long firstAvailableNumber,
        Long lastAvailableNumber,
        Long lag,
        Long changesApplied,
        Long changesIgnored,
        Long gapsDetected,
        Long errors,
        Instant startedAt,
        Instant lastPolledAt,
        Instant lastChangeAt,
        String lastError) {

    /** Configured off, or on but with nothing read yet. */
    public static ChangelogStatus notRunning(boolean enabled) {
        return new ChangelogStatus(
                enabled, false, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static ChangelogStatus from(ChangelogCursor cursor, boolean running) {
        return new ChangelogStatus(
                true,
                running,
                cursor.getLastChangeNumber(),
                cursor.getFirstAvailableNumber(),
                cursor.getLastAvailableNumber(),
                cursor.lag(),
                cursor.getChangesApplied(),
                cursor.getChangesIgnored(),
                cursor.getGapsDetected(),
                cursor.getErrors(),
                cursor.getStartedAt(),
                cursor.getLastPolledAt(),
                cursor.getLastChangeAt(),
                cursor.getLastError());
    }
}

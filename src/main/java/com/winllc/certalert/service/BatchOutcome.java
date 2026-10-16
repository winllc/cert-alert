package com.winllc.certalert.service;

/**
 * What one batch of directory entries did to the database.
 *
 * @param created entries seen for the first time
 * @param certificatesCached certificates newly cached
 * @param certificatesRemoved cached certificates the directory no longer publishes
 * @param alertsRaised certificates that moved into an alertable state
 * @param notAdded people seen for the first time but not added, because the license's size
 *     is used up (see {@code LicenseCheck#mayAdd}); they are added once it allows
 */
public record BatchOutcome(
        int created, int certificatesCached, int certificatesRemoved, int alertsRaised, int notAdded) {

    public static final BatchOutcome EMPTY = new BatchOutcome(0, 0, 0, 0, 0);

    public BatchOutcome(int created, int certificatesCached, int certificatesRemoved, int alertsRaised) {
        this(created, certificatesCached, certificatesRemoved, alertsRaised, 0);
    }

    public BatchOutcome plus(BatchOutcome other) {
        return new BatchOutcome(
                created + other.created,
                certificatesCached + other.certificatesCached,
                certificatesRemoved + other.certificatesRemoved,
                alertsRaised + other.alertsRaised,
                notAdded + other.notAdded);
    }
}

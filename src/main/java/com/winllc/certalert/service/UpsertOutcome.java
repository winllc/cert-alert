package com.winllc.certalert.service;

/**
 * What happened to one directory entry during a sync.
 *
 * @param created whether the entry was seen for the first time
 * @param certificatesCached certificates newly cached for it
 * @param certificatesRemoved cached certificates it no longer publishes
 * @param alertsRaised certificates that moved into an alertable state
 */
public record UpsertOutcome(boolean created, int certificatesCached, int certificatesRemoved, int alertsRaised) {

    public static UpsertOutcome of(boolean created, int cached, int removed, int alerts) {
        return new UpsertOutcome(created, cached, removed, alerts);
    }
}

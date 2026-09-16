package com.winllc.certalert.service;

import java.time.Duration;

/**
 * Summary of one sync run.
 *
 * @param usersSeen people read from the directory
 * @param usersCreated people seen for the first time
 * @param serversSeen servers read from the directory
 * @param serversCreated servers seen for the first time
 * @param certificatesCached certificates newly cached this run
 * @param certificatesRemoved cached certificates the directory no longer publishes
 * @param alertsRaised certificates that moved into an alertable state
 * @param errors entries that could not be processed
 * @param duration wall-clock time taken
 */
public record DirectorySyncResult(
        int usersSeen,
        int usersCreated,
        int serversSeen,
        int serversCreated,
        int certificatesCached,
        int certificatesRemoved,
        int alertsRaised,
        int errors,
        Duration duration) {}

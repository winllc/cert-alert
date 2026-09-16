package com.winllc.certalert.web.dto;

import com.winllc.certalert.service.DirectorySyncResult;

/** Outcome of an on-demand directory sync. */
public record SyncResponse(
        int usersSeen,
        int usersCreated,
        int serversSeen,
        int serversCreated,
        int certificatesCached,
        int certificatesRemoved,
        int alertsRaised,
        int errors,
        long durationMillis) {

    public static SyncResponse from(DirectorySyncResult result) {
        return new SyncResponse(
                result.usersSeen(),
                result.usersCreated(),
                result.serversSeen(),
                result.serversCreated(),
                result.certificatesCached(),
                result.certificatesRemoved(),
                result.alertsRaised(),
                result.errors(),
                result.duration().toMillis());
    }
}

package com.winllc.certalert.domain;

/** The scheduled jobs that keep the cache in step with the directory. */
public enum SyncJob {

    /** Scrapes IC Persons. */
    USERS,

    /** Scrapes IC Non-Person Entities. */
    SERVERS,

    /** Re-evaluates cached expiry without reading the directory. */
    REFRESH,

    /** Removes entries the directory no longer publishes. */
    PRUNE
}

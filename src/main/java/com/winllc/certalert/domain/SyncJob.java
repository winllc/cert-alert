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
    PRUNE,

    /** Asks the issuing authorities which cached certificates have been revoked. */
    REVOCATION,

    /** Removes certificates from the directory that it should not still be publishing. */
    CERTIFICATE_CLEANUP
}

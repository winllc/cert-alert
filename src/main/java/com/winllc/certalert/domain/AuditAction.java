package com.winllc.certalert.domain;

/**
 * What an audit record is about.
 *
 * <p>These are all <em>changes</em>. A sweep that finds a hundred thousand entries exactly
 * as it left them writes nothing here; the sweep itself is recorded in {@code sync_run},
 * and this table answers the other question - what happened to this entry, and who did it.
 */
public enum AuditAction {

    /**
     * The first sweep to see this entry. One record, however many certificates it arrived
     * with: they are what the entry was when first seen, not something that changed.
     */
    ENTRY_DISCOVERED("Discovered"),

    /** Deleted after going unseen for longer than the prune window. */
    ENTRY_PRUNED("Pruned"),

    /** A certificate appeared on an entry already known. */
    CERTIFICATE_CACHED("Certificate published"),

    /** The directory stopped publishing a certificate it had. */
    CERTIFICATE_REMOVED("Certificate withdrawn"),

    /** A cached certificate moved between valid, expiring and expired. */
    CERTIFICATE_STATUS_CHANGED("Certificate status changed"),

    CONTACT_ADDED("Point of contact added"),

    CONTACT_REMOVED("Point of contact removed"),

    /** An alert was handed to a delivery channel and the channel accepted it. */
    ALERT_SENT("Alert sent"),

    /** A delivery channel refused or failed. The alert happened; the delivery did not. */
    ALERT_FAILED("Alert failed");

    private final String label;

    AuditAction(String label) {
        this.label = label;
    }

    /** How the history reads it out. */
    public String label() {
        return label;
    }
}

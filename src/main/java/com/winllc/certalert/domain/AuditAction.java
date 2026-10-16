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

    /**
     * This application deleted a certificate from the directory entry. Distinct from the
     * record above, which is the directory having done it: that is something noticed, and
     * this is something done - the only thing here that removes data from somebody else's
     * system of record.
     */
    CERTIFICATE_DELETED("Certificate deleted from the directory"),

    /**
     * An authority was asked about a certificate and said it had been revoked. Recorded
     * against the entry because it is the most consequential thing that can be learned
     * about one, and it is learned from somewhere other than the directory.
     */
    CERTIFICATE_REVOKED("Certificate revoked"),

    /** A cached certificate moved between valid, expiring and expired. */
    CERTIFICATE_STATUS_CHANGED("Certificate status changed"),

    CONTACT_ADDED("Point of contact added"),

    CONTACT_REMOVED("Point of contact removed"),

    /** An address this person answers to, added here rather than published by the directory. */
    ADDRESS_ADDED("Address added"),

    ADDRESS_REMOVED("Address removed"),

    /** Given the running of a project: its servers' contacts, and its expiry notices. */
    PROJECT_ADMIN_ADDED("Made a project administrator"),

    PROJECT_ADMIN_REMOVED("No longer a project administrator"),

    /** A managed attribute was given a value, or given a different one. */
    ATTRIBUTE_SET("Attribute set"),

    /** A managed attribute was emptied. */
    ATTRIBUTE_CLEARED("Attribute cleared"),

    /** Put into a project, or taken out of one. */
    PROJECT_JOINED("Added to a project"),

    PROJECT_LEFT("Removed from a project"),

    /**
     * Somebody asked a server's endpoint what it was serving. Recorded because it is an
     * outbound connection this application made on somebody's behalf, and the answer
     * decided whether a certificate was believed to be installed.
     */
    ENDPOINT_PROBED("Endpoint probed"),

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

    /**
     * The {@code ens-audit/1} action id this kind of record is emitted as: the constant's
     * own name, lowercase and dotted. Stable: a SIEM searches on it, so it is never renamed.
     */
    public String id() {
        return switch (this) {
            case ENTRY_DISCOVERED -> "entry.discovered";
            case ENTRY_PRUNED -> "entry.pruned";
            case CERTIFICATE_CACHED -> "certificate.cached";
            case CERTIFICATE_REMOVED -> "certificate.removed";
            case CERTIFICATE_DELETED -> "certificate.deleted";
            case CERTIFICATE_REVOKED -> "certificate.revoked";
            case CERTIFICATE_STATUS_CHANGED -> "certificate.status-changed";
            case CONTACT_ADDED -> "contact.added";
            case CONTACT_REMOVED -> "contact.removed";
            case ADDRESS_ADDED -> "address.added";
            case ADDRESS_REMOVED -> "address.removed";
            case PROJECT_ADMIN_ADDED -> "project.admin-added";
            case PROJECT_ADMIN_REMOVED -> "project.admin-removed";
            case ATTRIBUTE_SET -> "attribute.set";
            case ATTRIBUTE_CLEARED -> "attribute.cleared";
            case PROJECT_JOINED -> "project.joined";
            case PROJECT_LEFT -> "project.left";
            case ENDPOINT_PROBED -> "endpoint.probed";
            case ALERT_SENT -> "alert.sent";
            case ALERT_FAILED -> "alert.failed";
        };
    }

    /**
     * Its {@code ens-audit/1} category. Handing somebody the running of a project is a role
     * granted, so {@code account}; an alert's delivery is the application's own doing,
     * {@code system}; everything else changes the data this application keeps.
     */
    public String category() {
        return switch (this) {
            case PROJECT_ADMIN_ADDED, PROJECT_ADMIN_REMOVED -> "account";
            case ALERT_SENT, ALERT_FAILED -> "system";
            default -> "data";
        };
    }

    /** {@code failure} for a delivery that failed; everything else here is something that happened. */
    public String outcome() {
        return this == ALERT_FAILED ? "failure" : "success";
    }

    /**
     * {@code notice} for removing data and for roles, {@code warning} for a revocation found
     * and for a failed delivery, {@code info} for the rest.
     */
    public String severity() {
        return switch (this) {
            case ENTRY_PRUNED, CERTIFICATE_DELETED, PROJECT_ADMIN_ADDED, PROJECT_ADMIN_REMOVED -> "notice";
            case CERTIFICATE_REVOKED, ALERT_FAILED -> "warning";
            default -> "info";
        };
    }

    /** The kind with this action id, or null. */
    public static AuditAction ofId(String id) {
        for (AuditAction action : values()) {
            if (action.id().equals(id)) {
                return action;
            }
        }
        return null;
    }
}

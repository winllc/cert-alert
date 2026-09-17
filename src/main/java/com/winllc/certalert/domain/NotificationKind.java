package com.winllc.certalert.domain;

/** Why somebody was told. */
public enum NotificationKind {

    /**
     * A certificate they are the contact for crossed into a state worth knowing about.
     * Raised the moment a sweep or the expiry re-evaluation notices, one per certificate.
     */
    CERTIFICATE_STATUS("Certificate status"),

    /**
     * The scheduled round-up: everything currently expiring or expired that they are the
     * contact for, in one. This is the one that goes out by email.
     */
    EXPIRY_DIGEST("Expiry summary");

    private final String label;

    NotificationKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

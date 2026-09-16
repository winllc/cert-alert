package com.winllc.certalert.domain;

/**
 * Validity state of a certificate, or the rolled-up state of all certificates held by a
 * directory entity.
 */
public enum CertificateStatus {

    /** No certificate at all. Only ever a roll-up value, never a single certificate's state. */
    NONE(false),

    /** Valid, and beyond the configured warning window. */
    VALID(false),

    /** Still valid, but expires inside the configured warning window. */
    EXPIRING_SOON(true),

    /** Past its notAfter date. */
    EXPIRED(true);

    private final boolean alertable;

    CertificateStatus(boolean alertable) {
        this.alertable = alertable;
    }

    /** Whether reaching this state should raise an alert. */
    public boolean isAlertable() {
        return alertable;
    }

    /**
     * Rolls several certificate states up into one for an entity holding them. The worst
     * state wins, so an entity with one expired and one valid certificate reads as EXPIRED
     * and stays visible to anyone filtering for problems.
     */
    public static CertificateStatus worstOf(Iterable<CertificateStatus> statuses) {
        CertificateStatus worst = NONE;
        for (CertificateStatus status : statuses) {
            if (status.ordinal() > worst.ordinal()) {
                worst = status;
            }
        }
        return worst;
    }
}

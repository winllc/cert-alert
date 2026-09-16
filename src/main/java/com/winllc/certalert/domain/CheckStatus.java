package com.winllc.certalert.domain;

/** Outcome of a single certificate check. */
public enum CheckStatus {

    /** Certificate is valid and not close enough to expiry to warrant an alert. */
    VALID(false),

    /** Certificate is still valid but expires within the configured warning window. */
    EXPIRING_SOON(true),

    /** Certificate is past its notAfter date. */
    EXPIRED(true),

    /** The endpoint could not be reached, or the TLS handshake failed. */
    UNREACHABLE(true);

    private final boolean alertable;

    CheckStatus(boolean alertable) {
        this.alertable = alertable;
    }

    /** Whether reaching this status should raise an alert. */
    public boolean isAlertable() {
        return alertable;
    }
}

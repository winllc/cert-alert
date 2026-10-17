package com.winllc.certalert.security;

import org.springframework.security.core.AuthenticationException;

/**
 * A client certificate the directory does publish for a person, refused because it can no
 * longer be relied on: its authority has revoked it, or its dates say it is not good now.
 *
 * <p>Its own type so the audit trail can say which of those it was. The message is that
 * reason, in a few words, and it is what {@code auth.login} records.
 */
public class RefusedCertificateException extends AuthenticationException {

    /** Why it was refused. */
    public enum Reason {
        REVOKED("certificate revoked"),
        EXPIRED("certificate expired"),
        NOT_YET_VALID("certificate not yet valid");

        private final String description;

        Reason(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    private final Reason reason;
    private final String fingerprint;

    public RefusedCertificateException(Reason reason, String fingerprint) {
        super(reason.description());
        this.reason = reason;
        this.fingerprint = fingerprint;
    }

    public Reason getReason() {
        return reason;
    }

    /** SHA-256 of the certificate, as the cache and the audit trail name it. */
    public String getFingerprint() {
        return fingerprint;
    }
}

package com.winllc.certalert.security;

import org.springframework.security.core.AuthenticationException;

/**
 * A client certificate refused for a reason worth recording: its authority has revoked it,
 * nobody could say whether it had, its dates say it is not good now, its issuer is not one
 * this deployment accepts, or - forwarded by a proxy - it did not come from the proxy or does
 * not chain to a trusted CA.
 *
 * <p>Its own type so the audit trail can say which of those it was. The message is that
 * reason, in a few words, and it is what {@code auth.login} records.
 */
public class RefusedCertificateException extends AuthenticationException {

    /** Why it was refused. */
    public enum Reason {
        REVOKED("certificate revoked"),
        REVOCATION_UNKNOWN("revocation unknown"),
        EXPIRED("certificate expired"),
        NOT_YET_VALID("certificate not yet valid"),
        ISSUER_NOT_ALLOWED("issuer not allowed"),
        UNTRUSTED("certificate not trusted"),
        UNTRUSTED_PROXY("certificate header not from a trusted proxy");

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

    /** SHA-256 of the certificate, as the cache and the audit trail name it; null where there was none to read. */
    public String getFingerprint() {
        return fingerprint;
    }
}

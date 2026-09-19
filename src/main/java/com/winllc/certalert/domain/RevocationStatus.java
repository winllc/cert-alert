package com.winllc.certalert.domain;

/**
 * What the issuing authority says about a certificate, as distinct from what its dates say.
 *
 * <p>Expiry is arithmetic: the certificate carries the answer and anybody can work it out.
 * Revocation is somebody else's decision, published somewhere else, and a revoked
 * certificate looks exactly like a good one until that somewhere else is asked. A key that
 * was reported stolen last March is still valid until 2028 as far as every other page here
 * is concerned.
 */
public enum RevocationStatus {

    /** Not asked yet, or nowhere to ask. The honest default, and not a clean bill of health. */
    NOT_CHECKED("Not checked", Severity.INFO),

    /** The authority was asked and does not list it. */
    GOOD("Not revoked", Severity.INFO),

    /** The authority lists it. Whatever its dates say, it should not be in use. */
    REVOKED("Revoked", Severity.CRITICAL),

    /**
     * Asked, and no answer: the responder was unreachable, the CRL would not parse, the
     * certificate names nowhere to ask. Deliberately not GOOD - an unanswered question is
     * not a negative answer, and reporting it as one is how a revoked certificate stays in
     * service.
     */
    UNKNOWN("Unknown", Severity.WARNING);

    private final String label;
    private final Severity severity;

    RevocationStatus(String label, Severity severity) {
        this.label = label;
        this.severity = severity;
    }

    public String label() {
        return label;
    }

    public Severity severity() {
        return severity;
    }
}

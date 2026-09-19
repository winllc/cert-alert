package com.winllc.certalert.domain;

/**
 * What a probe of a server's endpoint found.
 *
 * <p>The question being asked is whether the server is serving the certificate the
 * directory says it has. The directory is the record of what was issued; only the endpoint
 * knows what was installed, and those two drift apart in one direction - a renewal recorded
 * and never deployed, which looks perfect on every page here and expires in production.
 */
public enum ProbeFinding {

    /** What the endpoint presented is the most recently issued certificate published. */
    SERVING_CURRENT("Serving the current certificate", Severity.INFO),

    /**
     * The endpoint's certificate is one the directory publishes, but not the newest one.
     * The renewal happened and was never installed, which is the whole point of looking.
     */
    SERVING_SUPERSEDED("Serving a superseded certificate", Severity.CRITICAL),

    /** The directory has never published what the endpoint is serving. */
    NOT_PUBLISHED("Certificate not published by the directory", Severity.CRITICAL),

    /** Nothing cached for this entry, so there is nothing to compare against. */
    NOTHING_PUBLISHED("Nothing published to compare with", Severity.WARNING),

    EXPIRED("Presented certificate has expired", Severity.CRITICAL),

    NOT_YET_VALID("Presented certificate is not valid yet", Severity.CRITICAL),

    /** The name that was asked for is not one the certificate is good for. */
    NAME_MISMATCH("Name does not match the certificate", Severity.WARNING),

    /** Issuer and subject are the same: nothing vouches for it but itself. */
    SELF_SIGNED("Self-signed", Severity.WARNING),

    /**
     * The leaf and nothing else. A client that does not already hold the intermediate
     * cannot build a path, which is the failure that works on one machine and not another.
     */
    NO_INTERMEDIATES("No intermediate certificates sent", Severity.WARNING);

    private final String label;
    private final Severity severity;

    ProbeFinding(String label, Severity severity) {
        this.label = label;
        this.severity = severity;
    }

    public String label() {
        return label;
    }

    public Severity severity() {
        return severity;
    }

    /** Whether this is a finding to act on rather than to note. */
    public boolean isProblem() {
        return severity == Severity.CRITICAL;
    }

    /**
     * Whether this is the answer to what was asked - is the endpoint on the certificate the
     * directory published - rather than something noticed along the way. Exactly one of
     * these is ever present, and it leads, so the card does not open with a remark about
     * intermediates while the answer sits underneath it.
     */
    public boolean answersTheQuestion() {
        return this == SERVING_CURRENT
                || this == SERVING_SUPERSEDED
                || this == NOT_PUBLISHED
                || this == NOTHING_PUBLISHED;
    }
}

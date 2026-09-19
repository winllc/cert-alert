package com.winllc.certalert.domain;

/**
 * What is wrong with the set of certificates an entry currently holds.
 *
 * <p>About the set rather than any one certificate: a person holds a signing certificate
 * and a key encipherment certificate, issued together, and the problems worth surfacing are
 * the ones only visible when both are looked at at once. Half a renewal leaves a person able
 * to sign but not to be written to, and nothing about either certificate on its own says so.
 */
public enum CredentialIssue {

    MISSING_SIGNING(
            "No signing certificate",
            "Nothing current carries the digital signature bit, so this person cannot sign"),

    MISSING_ENCRYPTION(
            "No encryption certificate",
            "Nothing current carries the key encipherment bit, so nothing can be encrypted to this person"),

    ISSUED_APART(
            "Pair issued apart",
            "The signing and encryption certificates were issued too far apart to be one issuance, "
                    + "which usually means one was renewed and the other was not"),

    /**
     * Not a fault in the directory: the key usage extension is read when a certificate is
     * parsed, so anything cached before that was added carries no use until the next sweep
     * re-reads it. Saying so is better than reporting both halves missing.
     */
    USE_NOT_KNOWN(
            "Use not known",
            "These certificates were cached before key usage was read; the next sweep will say which is which");

    private final String label;
    private final String why;

    CredentialIssue(String label, String why) {
        this.label = label;
        this.why = why;
    }

    public String label() {
        return label;
    }

    /** The explanation, for a tooltip. */
    public String why() {
        return why;
    }
}

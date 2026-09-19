package com.winllc.certalert.domain;

import java.util.Set;

/**
 * What a certificate is for, decided from its key usage bits.
 *
 * <p>A person here holds two: one that signs and one that encrypts, issued together. This
 * is the distinction that says which is which, so the two can be seen as the one credential
 * they are - and so a person missing half of it is visible.
 *
 * <p>A server's certificate is usually {@link #DUAL}, since a TLS key both signs the
 * handshake and, under an RSA key exchange, receives the premaster secret. That is not a
 * fault; the distinction is only load-bearing for people.
 */
public enum CertificateUse {

    /** Signs. Digital signature or non-repudiation, without any encipherment bit. */
    SIGNING("Signing", "Digital signature"),

    /** Encrypts to. Key encipherment, data encipherment or key agreement, without signing. */
    ENCRYPTION("Encryption", "Key encipherment"),

    /** Both, which is ordinary for a server and unusual for a person. */
    DUAL("Signing and encryption", "Digital signature and key encipherment"),

    /** Something else entirely: a CA certificate, or one that only signs CRLs. */
    OTHER("Other", "Neither signing nor encryption"),

    /** No key usage extension, so the certificate restricts nothing and says nothing. */
    UNSPECIFIED("Unspecified", "No key usage extension");

    private final String label;
    private final String description;

    CertificateUse(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    /** The longer form, for a tooltip. */
    public String description() {
        return description;
    }

    /** Whether this certificate is the signing half of a person's pair. */
    public boolean signs() {
        return this == SIGNING || this == DUAL;
    }

    /** Whether it is the encryption half. */
    public boolean encrypts() {
        return this == ENCRYPTION || this == DUAL;
    }

    /**
     * What the bits add up to.
     *
     * <p>Certificate and CRL signing are read first and on their own: a CA certificate
     * carries digitalSignature as well more often than not, and calling it a signing
     * credential would put it in the pair.
     */
    public static CertificateUse from(Set<KeyUsage> usages) {
        if (usages == null || usages.isEmpty()) {
            return UNSPECIFIED;
        }
        if (usages.contains(KeyUsage.KEY_CERT_SIGN) || usages.contains(KeyUsage.CRL_SIGN)) {
            return OTHER;
        }
        boolean signs = usages.contains(KeyUsage.DIGITAL_SIGNATURE) || usages.contains(KeyUsage.NON_REPUDIATION);
        boolean encrypts = usages.contains(KeyUsage.KEY_ENCIPHERMENT)
                || usages.contains(KeyUsage.DATA_ENCIPHERMENT)
                || usages.contains(KeyUsage.KEY_AGREEMENT);
        if (signs && encrypts) {
            return DUAL;
        }
        if (signs) {
            return SIGNING;
        }
        return encrypts ? ENCRYPTION : OTHER;
    }
}

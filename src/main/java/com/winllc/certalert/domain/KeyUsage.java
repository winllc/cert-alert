package com.winllc.certalert.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * What a certificate's key is allowed to do, from the X.509 key usage extension.
 *
 * <p>The extension is a bit string, and its nine bits are positional: the order here is the
 * order in the certificate, and {@link #of(boolean[])} depends on it.
 *
 * <p>This matters here because a person in this directory holds two certificates, not one.
 * PKI for people separates signing from encryption - a signing key must never be escrowed,
 * an encryption key usually must be, so they cannot be the same key - and both are issued
 * at once. Telling them apart is what lets the pair be treated as the one credential it is.
 */
public enum KeyUsage {

    DIGITAL_SIGNATURE("Digital signature"),
    NON_REPUDIATION("Non-repudiation"),
    KEY_ENCIPHERMENT("Key encipherment"),
    DATA_ENCIPHERMENT("Data encipherment"),
    KEY_AGREEMENT("Key agreement"),
    KEY_CERT_SIGN("Certificate signing"),
    CRL_SIGN("CRL signing"),
    ENCIPHER_ONLY("Encipher only"),
    DECIPHER_ONLY("Decipher only");

    private final String label;

    KeyUsage(String label) {
        this.label = label;
    }

    /** How the page prints it. */
    public String label() {
        return label;
    }

    /**
     * Reads the extension as {@code X509Certificate.getKeyUsage()} returns it.
     *
     * @param bits nine flags in extension order, or null where the certificate carries no
     *     key usage extension at all - which is allowed, and means nothing is restricted
     */
    public static Set<KeyUsage> of(boolean[] bits) {
        Set<KeyUsage> usages = EnumSet.noneOf(KeyUsage.class);
        if (bits == null) {
            return usages;
        }
        KeyUsage[] values = values();
        for (int i = 0; i < values.length && i < bits.length; i++) {
            if (bits[i]) {
                usages.add(values[i]);
            }
        }
        return usages;
    }
}

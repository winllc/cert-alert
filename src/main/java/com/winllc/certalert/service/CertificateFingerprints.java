package com.winllc.certalert.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

/**
 * The identity of a certificate: lowercase hex SHA-256 over its DER encoding, which is
 * what openssl and browsers show.
 *
 * <p>Shared by the scrape, which caches certificates under this value, and by X.509
 * authentication, which uses it to recognise a presented client certificate as one the
 * directory publishes.
 */
public final class CertificateFingerprints {

    private CertificateFingerprints() {}

    public static String sha256(byte[] der) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(der));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform but was unavailable", e);
        }
    }

    public static String sha256(X509Certificate certificate) {
        try {
            return sha256(certificate.getEncoded());
        } catch (CertificateEncodingException e) {
            throw new IllegalArgumentException("Certificate could not be encoded", e);
        }
    }
}

package com.winllc.certalert.service;

import java.time.Instant;
import java.util.List;

/**
 * The parts of a leaf certificate this application cares about.
 *
 * @param subject distinguished name of the certificate subject
 * @param issuer distinguished name of the issuing CA
 * @param serialNumber serial number in hexadecimal
 * @param notBefore start of the validity window
 * @param notAfter end of the validity window
 * @param subjectAlternativeNames DNS names the certificate is valid for
 * @param signatureAlgorithm algorithm the issuer signed the certificate with
 */
public record CertificateDetails(
        String subject,
        String issuer,
        String serialNumber,
        Instant notBefore,
        Instant notAfter,
        List<String> subjectAlternativeNames,
        String signatureAlgorithm) {

    public CertificateDetails {
        subjectAlternativeNames = subjectAlternativeNames == null ? List.of() : List.copyOf(subjectAlternativeNames);
    }
}

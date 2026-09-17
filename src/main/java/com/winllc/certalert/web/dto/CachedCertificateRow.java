package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import java.time.Instant;

/** Cached details of one certificate, shown when a row is expanded. */
public record CachedCertificateRow(
        Long id,
        String sha256Fingerprint,
        String serialNumber,
        String subjectDn,
        String issuerDn,
        Instant notBefore,
        Instant notAfter,
        String signatureAlgorithm,
        String hashAlgorithm,
        String keyAlgorithm,
        Integer keySize,
        String subjectAlternativeNames,
        CertificateStatus status,
        Instant cachedAt) {

    public static CachedCertificateRow from(CachedCertificate certificate) {
        return new CachedCertificateRow(
                certificate.getId(),
                certificate.getSha256Fingerprint(),
                certificate.getSerialNumber(),
                certificate.getSubjectDn(),
                certificate.getIssuerDn(),
                certificate.getNotBefore(),
                certificate.getNotAfter(),
                certificate.getSignatureAlgorithm(),
                certificate.getHashAlgorithm(),
                certificate.getKeyAlgorithm(),
                certificate.getKeySize(),
                certificate.getSubjectAlternativeNames(),
                certificate.getStatus(),
                certificate.getCachedAt());
    }
}

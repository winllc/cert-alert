package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateRisk;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.KeyUsage;
import java.time.Instant;
import java.util.List;

/**
 * Cached details of one certificate, shown when a row is expanded.
 *
 * @param subjectAltNameCount how many names it is good for, which is more than the stored
 *     list says when that list was truncated
 * @param risks what is worrying about those names, with the words to print
 * @param use what the certificate is for, which is what tells a person's signing
 *     certificate from their encryption one
 * @param keyUsages the key usage bits it was decided from, spelled out
 */
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
        Integer subjectAltNameCount,
        List<Risk> risks,
        String use,
        String useDescription,
        List<String> keyUsages,
        CertificateStatus status,
        Instant cachedAt) {

    /** One flag, as the page shows it. */
    public record Risk(String name, String label, String why, boolean severe) {

        static Risk of(CertificateRisk risk) {
            return new Risk(risk.name(), risk.label(), risk.why(), risk.isSevere());
        }
    }

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
                certificate.getSubjectAltNameCount(),
                certificate.getRisks().stream().map(Risk::of).toList(),
                certificate.getUse().label(),
                certificate.getUse().description(),
                certificate.getKeyUsages().stream().map(KeyUsage::label).toList(),
                certificate.getStatus(),
                certificate.getCachedAt());
    }
}

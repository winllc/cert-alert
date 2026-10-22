package com.winllc.certalert.inventory;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateRisk;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.OwnerType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One certificate in the inventory, with who holds it: a row of the API's list, the page's
 * table and the export alike.
 *
 * @param daysUntilExpiry whole days from now until it expires, negative once it has
 * @param pointsOfContact for a server's certificate, its directory points of contact as shown
 * @param risks flag names, where risk analysis is licensed; empty otherwise
 */
public record CertificateInventoryRow(
        Long id,
        OwnerType ownerType,
        Long ownerId,
        String ownerName,
        String ownerDn,
        String ownerEmail,
        String pointsOfContact,
        String subjectDn,
        String issuerDn,
        String serialNumber,
        String sha256Fingerprint,
        Instant notBefore,
        Instant notAfter,
        Long daysUntilExpiry,
        CertificateStatus status,
        String keyAlgorithm,
        Integer keySize,
        String signatureAlgorithm,
        String hashAlgorithm,
        String subjectAlternativeNames,
        String use,
        String revocationStatus,
        Instant revokedAt,
        List<String> risks,
        Instant cachedAt) {

    static CertificateInventoryRow of(CachedCertificate certificate, Instant now, boolean risksShown) {
        DirectoryUser user = certificate.getUser();
        DirectoryServer server = certificate.getServer();
        OwnerType type = server != null ? OwnerType.SERVER : OwnerType.USER;
        Long ownerId = server != null ? server.getId() : user != null ? user.getId() : null;
        String ownerName = server != null ? firstOf(server.getCommonName(), server.getUid(), server.getDn())
                : user != null ? firstOf(user.getDisplayName(), user.getCommonName(), user.getUid(), user.getDn()) : null;
        String ownerDn = server != null ? server.getDn() : user != null ? user.getDn() : null;
        String email = user != null ? user.getEmail() : null;
        String contacts = server != null ? server.getServerPocDisplay() : null;
        Long days = certificate.getNotAfter() == null ? null
                : Math.floorDiv(Duration.between(now, certificate.getNotAfter()).toHours(), 24);
        return new CertificateInventoryRow(
                certificate.getId(),
                type,
                ownerId,
                ownerName,
                ownerDn,
                email,
                contacts,
                certificate.getSubjectDn(),
                certificate.getIssuerDn(),
                certificate.getSerialNumber(),
                certificate.getSha256Fingerprint(),
                certificate.getNotBefore(),
                certificate.getNotAfter(),
                days,
                certificate.getStatus(),
                certificate.getKeyAlgorithm(),
                certificate.getKeySize(),
                certificate.getSignatureAlgorithm(),
                certificate.getHashAlgorithm(),
                certificate.getSubjectAlternativeNames(),
                certificate.getUse().label(),
                certificate.getRevocationStatus().name(),
                certificate.getRevokedAt(),
                risksShown ? certificate.getRisks().stream().map(CertificateRisk::name).toList() : List.of(),
                certificate.getCachedAt());
    }

    private static String firstOf(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

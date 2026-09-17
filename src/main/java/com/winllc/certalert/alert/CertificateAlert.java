package com.winllc.certalert.alert;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.Severity;
import java.time.Instant;

/**
 * A certificate that has just moved into a state worth telling someone about.
 *
 * @param ownerType whether the certificate belongs to a user or a server
 * @param ownerId the owner's id, so what happened can be recorded against them
 * @param ownerName display name of the owner
 * @param ownerDn distinguished name of the owner in the directory
 * @param contact who to chase: the user's own address, or a server's points of contact
 * @param status the state the certificate moved into
 * @param severity how urgent it is
 * @param certificateSubject subject DN of the certificate
 * @param certificateFingerprint SHA-256 fingerprint, which identifies the certificate itself
 * @param serialNumber certificate serial, in hexadecimal
 * @param notAfter when the certificate expires
 * @param daysUntilExpiry days remaining, negative once expired
 * @param raisedAt when the alert was raised
 */
public record CertificateAlert(
        OwnerType ownerType,
        Long ownerId,
        String ownerName,
        String ownerDn,
        String contact,
        CertificateStatus status,
        Severity severity,
        String certificateSubject,
        String certificateFingerprint,
        String serialNumber,
        Instant notAfter,
        Long daysUntilExpiry,
        Instant raisedAt) {

    public static CertificateAlert from(
            OwnerType ownerType,
            Long ownerId,
            String ownerName,
            String ownerDn,
            String contact,
            CachedCertificate certificate,
            Severity severity,
            Long daysUntilExpiry,
            Instant raisedAt) {
        return new CertificateAlert(
                ownerType,
                ownerId,
                ownerName,
                ownerDn,
                contact,
                certificate.getStatus(),
                severity,
                certificate.getSubjectDn(),
                certificate.getSha256Fingerprint(),
                certificate.getSerialNumber(),
                certificate.getNotAfter(),
                daysUntilExpiry,
                raisedAt);
    }

    /** A one-line summary suitable for a log line, a chat message, or an email subject. */
    public String summary() {
        String owner = "%s %s".formatted(ownerType == OwnerType.USER ? "user" : "server", ownerName);
        return switch (status) {
            case EXPIRED -> "[%s] Certificate for %s expired on %s (serial %s)"
                    .formatted(severity, owner, notAfter, serialNumber);
            case EXPIRING_SOON -> "[%s] Certificate for %s expires in %d day(s), on %s (serial %s)"
                    .formatted(severity, owner, daysUntilExpiry, notAfter, serialNumber);
            case VALID, NONE -> "[%s] Certificate for %s is valid until %s".formatted(severity, owner, notAfter);
        };
    }
}

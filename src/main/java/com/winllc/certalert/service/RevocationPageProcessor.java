package com.winllc.certalert.service;

import com.winllc.certalert.config.CredentialProperties;
import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.DirectoryEntry;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.RevocationStatus;
import com.winllc.certalert.repository.CachedCertificateRepository;
import com.winllc.certalert.revocation.RevocationChecker;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One page of the revocation check: a batch of entries, asked about and written back.
 *
 * <p>A bean of its own so each page is its own transaction. The check goes out over the
 * network - to a CRL distribution point, and the first entry through pays for the download
 * - and a single transaction spanning a hundred thousand entries would hold a database
 * connection open across every one of those fetches.
 *
 * <p>Entries rather than certificates, because which certificates are current is a question
 * about the entry: a person holds a signing certificate and an encryption certificate, and
 * the pair is what they are working with. Superseded certificates are left alone - an
 * authority revoking one that has already been replaced is not news.
 */
@Service
public class RevocationPageProcessor {

    private static final Logger log = LoggerFactory.getLogger(RevocationPageProcessor.class);

    private final CachedCertificateRepository certificates;
    private final RevocationChecker checker;
    private final NotificationService notifications;
    private final AuditService audit;
    private final CredentialProperties credentials;
    private final PublishedCertificates published;

    public RevocationPageProcessor(
            CachedCertificateRepository certificates,
            RevocationChecker checker,
            NotificationService notifications,
            AuditService audit,
            CredentialProperties credentials,
            PublishedCertificates published) {
        this.certificates = certificates;
        this.checker = checker;
        this.notifications = notifications;
        this.audit = audit;
        this.credentials = credentials;
        this.published = published;
    }

    /** What one page did, and where the next one starts. */
    public record Page(int entries, int checked, int revoked, int unknown, long lastOwnerId) {

        static final Page EMPTY = new Page(0, 0, 0, 0, 0);
    }

    @Transactional
    public Page check(OwnerType type, long after, int size, Instant now) {
        List<Long> owners = type == OwnerType.USER
                ? certificates.findUserIdsAfter(after, PageRequest.of(0, size))
                : certificates.findServerIdsAfter(after, PageRequest.of(0, size));
        if (owners.isEmpty()) {
            return Page.EMPTY;
        }
        List<CachedCertificate> all = type == OwnerType.USER
                ? certificates.findForUsers(owners)
                : certificates.findForServers(owners);

        Map<Long, List<CachedCertificate>> byOwner = new LinkedHashMap<>();
        for (CachedCertificate certificate : all) {
            byOwner.computeIfAbsent(ownerIdOf(certificate, type), key -> new ArrayList<>()).add(certificate);
        }

        int checkedCount = 0;
        int revokedCount = 0;
        int unknownCount = 0;
        for (List<CachedCertificate> owned : byOwner.values()) {
            List<CachedCertificate> current =
                    CertificateIssuance.current(owned, credentials.getPairWindow(), now).current();
            // The bytes, where a responder is going to be asked about one of these. A read
            // of the entry buys nothing otherwise, so it is not paid for: a deployment
            // whose certificates name their distribution points reads the directory no
            // more than it did before.
            Map<String, X509Certificate> leaves = leavesFor(current, type);

            for (CachedCertificate certificate : current) {
                RevocationStatus before = certificate.getRevocationStatus();
                RevocationChecker.Outcome outcome =
                        checker.check(certificate, leaves.get(certificate.getSha256Fingerprint()), now);
                certificate.recordRevocation(
                        outcome.status(),
                        outcome.method(),
                        outcome.revokedAt(),
                        outcome.reason(),
                        outcome.detail(),
                        now);
                checkedCount++;
                if (outcome.status() == RevocationStatus.UNKNOWN) {
                    unknownCount++;
                }
                if (outcome.isRevoked()) {
                    revokedCount++;
                    // Only on the transition: a certificate that was revoked last night is
                    // still revoked tonight, and telling everybody again every night is how
                    // people learn to ignore it.
                    if (before != RevocationStatus.REVOKED) {
                        announce(certificate, type, now);
                    }
                }
            }
        }
        return new Page(owners.size(), checkedCount, revokedCount, unknownCount, owners.getLast());
    }

    /**
     * The certificates of this entry as the directory publishes them, or nothing.
     *
     * <p>Asked for only where a responder is the only way to answer: no list to fetch,
     * a responder address from the certificate or the configuration, and an issuer
     * certificate to name the certificate by. That is the check the scheduled job could
     * not make before - a list needs only the cached serial number, and OCSP needs the
     * certificate itself, so a job working from the cache alone could never ask a
     * responder anything - and it stays off the path of every deployment whose
     * certificates name their distribution points.
     */
    private Map<String, X509Certificate> leavesFor(List<CachedCertificate> current, OwnerType type) {
        DirectoryEntry entry = null;
        for (CachedCertificate certificate : current) {
            if (checker.onlyAResponderCanAnswer(certificate)) {
                entry = type == OwnerType.USER ? certificate.getUser() : certificate.getServer();
                break;
            }
        }
        return entry == null ? Map.of() : published.of(entry, type);
    }

    /** Records it against the entry and tells whoever is responsible for it. */
    private void announce(CachedCertificate certificate, OwnerType type, Instant now) {
        AuditEvent.SubjectRef subject = subjectOf(certificate, type);
        log.warn("Certificate {} (serial {}) for '{}' has been revoked: {}",
                certificate.getSha256Fingerprint(),
                certificate.getSerialNumber(),
                subject.dn(),
                certificate.getRevocationDetail());

        audit.record(AuditEvent.about(
                        subject,
                        AuditAction.CERTIFICATE_REVOKED,
                        "Revoked%s%s, according to %s".formatted(
                                certificate.getRevokedAt() == null ? "" : " on " + certificate.getRevokedAt(),
                                certificate.getRevocationReason() == null
                                        ? ""
                                        : " (" + certificate.getRevocationReason() + ")",
                                certificate.getRevocationDetail()),
                        now)
                .by(AuditActors.REVOCATION)
                .forCertificate(certificate.getSha256Fingerprint()));

        notifications.notifyRevoked(certificate, subject, type);
    }

    private static Long ownerIdOf(CachedCertificate certificate, OwnerType type) {
        if (type == OwnerType.USER) {
            DirectoryUser user = certificate.getUser();
            return user == null ? null : user.getId();
        }
        DirectoryServer server = certificate.getServer();
        return server == null ? null : server.getId();
    }

    private static AuditEvent.SubjectRef subjectOf(CachedCertificate certificate, OwnerType type) {
        if (type == OwnerType.USER) {
            return AuditEvent.SubjectRef.of(certificate.getUser());
        }
        return AuditEvent.SubjectRef.of(certificate.getServer());
    }
}

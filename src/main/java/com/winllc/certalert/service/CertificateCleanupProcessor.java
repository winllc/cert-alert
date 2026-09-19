package com.winllc.certalert.service;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.DirectoryEntry;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.ldap.LdapCertificateStore;
import com.winllc.certalert.ldap.LdapProperties;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.NameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One entry's worth of the cleanup: read what it publishes, delete what it should not, and
 * bring the cache into line.
 *
 * <p>A bean of its own so each entry is its own transaction. The work in the middle is an
 * LDAP search and an LDAP modify, and a single transaction spanning five hundred entries
 * would hold a database connection open across a thousand round trips to the directory.
 * Per entry also means a directory that refuses one of them costs that one and no more.
 *
 * <p>The directory is written first and the cache second, deliberately. If the modify
 * succeeds and this process dies before the cached rows are deleted, the next sweep notices
 * the certificate has gone and removes them - the cache is derived from the directory, and
 * correcting itself is what it does. The other order would leave a certificate deleted from
 * the index and still published.
 */
@Service
public class CertificateCleanupProcessor {

    private static final Logger log = LoggerFactory.getLogger(CertificateCleanupProcessor.class);

    private final DirectoryUserRepository users;
    private final DirectoryServerRepository servers;
    private final LdapCertificateStore store;
    private final LdapProperties properties;
    private final AuditService audit;

    public CertificateCleanupProcessor(
            DirectoryUserRepository users,
            DirectoryServerRepository servers,
            LdapCertificateStore store,
            LdapProperties properties,
            AuditService audit) {
        this.users = users;
        this.servers = servers;
        this.store = store;
        this.properties = properties;
        this.audit = audit;
    }

    /**
     * What happened to one entry.
     *
     * @param removed how many values the directory no longer carries because of this
     * @param alreadyGone how many were not there to remove, which is an ordinary outcome -
     *     somebody may have got there first
     * @param refused whether the directory would not have it, which is nearly always its
     *     access control rather than anything about the certificate
     * @param missing whether the entry is not there at all any more - which is not a
     *     refusal, and saying so would send somebody looking through access control for a
     *     reason that is not there
     */
    public record Outcome(int removed, int alreadyGone, boolean refused, boolean missing) {

        static final Outcome NOTHING = new Outcome(0, 0, false, false);
    }

    /**
     * Removes these certificates from one entry.
     *
     * @param ids the cached certificates to remove, all belonging to that entry
     */
    @Transactional
    public Outcome clean(OwnerType type, Long ownerId, List<Long> ids, String actor, Instant now) {
        Optional<? extends DirectoryEntry> found = type == OwnerType.USER
                ? users.findWithCertificatesById(ownerId)
                : servers.findWithCertificatesById(ownerId);
        if (found.isEmpty()) {
            return Outcome.NOTHING;
        }
        DirectoryEntry entry = found.get();
        List<CachedCertificate> wanted = entry.getCertificates().stream()
                .filter(certificate -> ids.contains(certificate.getId()))
                .toList();
        if (wanted.isEmpty()) {
            return Outcome.NOTHING;
        }

        String attribute = type == OwnerType.USER
                ? properties.getUser().getCertificate()
                : properties.getServer().getCertificate();

        // Which values in the directory are the ones being removed. Matched by fingerprint
        // rather than by position, because the directory's order is its own business and
        // an attribute may have gained or lost values since the last sweep.
        Map<String, byte[]> published;
        try {
            published = byFingerprint(store.read(entry.getDn(), attribute));
        } catch (NameNotFoundException e) {
            // Deleted since the last sweep. The cached rows are stale, but what becomes of
            // an entry the directory has stopped publishing is the prune's decision and
            // not this job's - it removes certificates, not entries.
            log.debug("'{}' is no longer in the directory; nothing to clean up", entry.getDn());
            return new Outcome(0, 0, false, true);
        } catch (RuntimeException e) {
            log.warn("Could not read the certificates of '{}': {}", entry.getDn(), describe(e));
            return new Outcome(0, 0, true, false);
        }

        List<byte[]> removing = new ArrayList<>();
        List<CachedCertificate> gone = new ArrayList<>();
        for (CachedCertificate certificate : wanted) {
            byte[] value = published.get(certificate.getSha256Fingerprint());
            if (value == null) {
                // Not published any more. Nothing to delete, and the cached row is stale.
                gone.add(certificate);
            } else {
                removing.add(value);
            }
        }

        if (!removing.isEmpty()) {
            try {
                store.remove(entry.getDn(), attribute, removing);
            } catch (RuntimeException e) {
                // Almost always the directory's access control, which has the last word on
                // this and should: it is being asked to delete published data.
                log.warn("The directory refused to remove {} certificate(s) from '{}': {}",
                        removing.size(), entry.getDn(), describe(e));
                return new Outcome(0, 0, true, false);
            }
        }

        for (CachedCertificate certificate : wanted) {
            if (!gone.contains(certificate)) {
                audit.record(AuditEvent.about(subjectOf(entry, type), AuditAction.CERTIFICATE_DELETED,
                                summary(certificate), now)
                        .by(actor)
                        .forCertificate(certificate.getSha256Fingerprint()));
            }
        }
        // Removing them from the entry's collection is what deletes the rows: the mapping
        // is orphanRemoval, the same route the sweep takes when the directory withdraws
        // one, so there is one way a cached certificate stops existing rather than two.
        entry.getCertificates().removeAll(wanted);
        entry.refreshCertificateSummary();
        save(entry, type);

        return new Outcome(wanted.size() - gone.size(), gone.size(), false, false);
    }

    private static String summary(CachedCertificate certificate) {
        String why = certificate.isRevoked()
                ? "revoked%s".formatted(
                        certificate.getRevocationReason() == null
                                ? ""
                                : " (" + certificate.getRevocationReason() + ")")
                : "expired on " + certificate.getNotAfter();
        return "Deleted certificate %s from the directory entry: %s".formatted(certificate.getSerialNumber(), why);
    }

    private static Map<String, byte[]> byFingerprint(List<byte[]> values) {
        Map<String, byte[]> byFingerprint = new java.util.HashMap<>();
        for (byte[] value : values) {
            byFingerprint.put(CertificateFingerprints.sha256(value), value);
        }
        return byFingerprint;
    }

    private void save(DirectoryEntry entry, OwnerType type) {
        if (type == OwnerType.USER) {
            users.save((DirectoryUser) entry);
        } else {
            servers.save((DirectoryServer) entry);
        }
    }

    private static AuditEvent.SubjectRef subjectOf(DirectoryEntry entry, OwnerType type) {
        return type == OwnerType.USER
                ? AuditEvent.SubjectRef.of((DirectoryUser) entry)
                : AuditEvent.SubjectRef.of((DirectoryServer) entry);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}

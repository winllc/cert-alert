package com.winllc.certalert.service;

import com.winllc.certalert.alert.AlertDispatcher;
import com.winllc.certalert.alert.CertificateAlert;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryEntry;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.ldap.LdapServerEntry;
import com.winllc.certalert.ldap.LdapUserEntry;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles what the directory says with what is already cached.
 *
 * <p>Each entry is written in its own transaction so a single malformed entry cannot roll
 * back a whole sync. Certificates are matched on their SHA-256 fingerprint, so a re-sync
 * of an unchanged directory leaves the cache alone apart from refreshing expiry state.
 */
@Service
public class DirectoryPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(DirectoryPersistenceService.class);

    private final DirectoryUserRepository userRepository;
    private final DirectoryServerRepository serverRepository;
    private final CertificateParser certificateParser;
    private final CertificateStatusEvaluator evaluator;
    private final AlertDispatcher alertDispatcher;

    public DirectoryPersistenceService(
            DirectoryUserRepository userRepository,
            DirectoryServerRepository serverRepository,
            CertificateParser certificateParser,
            CertificateStatusEvaluator evaluator,
            AlertDispatcher alertDispatcher) {
        this.userRepository = userRepository;
        this.serverRepository = serverRepository;
        this.certificateParser = certificateParser;
        this.evaluator = evaluator;
        this.alertDispatcher = alertDispatcher;
    }

    @Transactional
    public UpsertOutcome upsertUser(LdapUserEntry entry, Instant now) {
        DirectoryUser user = userRepository.findByDn(entry.dn()).orElse(null);
        boolean created = user == null;
        if (created) {
            user = new DirectoryUser(entry.dn());
        }

        user.setUid(entry.uid());
        user.setCommonName(entry.commonName());
        user.setDisplayName(entry.displayName());
        user.setGivenName(entry.givenName());
        user.setSurname(entry.surname());
        user.setEmail(entry.email());
        user.setTelephoneNumber(entry.telephoneNumber());
        user.setTitle(entry.title());
        user.setEmployeeType(entry.employeeType());
        user.setCountry(entry.country());
        user.setOrganization(entry.organization());
        user.setOrganizationalUnit(entry.organizationalUnit());

        DirectoryUser owner = user;
        CertificateReconciliation reconciliation = reconcileCertificates(
                user,
                entry.certificates(),
                now,
                OwnerType.USER,
                displayNameOf(user),
                user.getEmail(),
                owner::addCertificate,
                owner::removeCertificate);

        user.markSynced(now);
        user.refreshCertificateSummary();
        userRepository.save(user);

        return UpsertOutcome.of(
                created, reconciliation.cached(), reconciliation.removed(), reconciliation.alertsRaised());
    }

    @Transactional
    public UpsertOutcome upsertServer(LdapServerEntry entry, Instant now) {
        DirectoryServer server = serverRepository.findByDn(entry.dn()).orElse(null);
        boolean created = server == null;
        if (created) {
            server = new DirectoryServer(entry.dn());
        }

        server.setCommonName(entry.commonName());
        server.setFqdn(entry.fqdn());
        server.setDescription(entry.description());
        server.setSerialNumber(entry.serialNumber());
        server.setOperatingSystem(entry.operatingSystem());
        server.setServerPocs(entry.serverPocs());
        server.setOrganization(entry.organization());
        server.setOrganizationalUnit(entry.organizationalUnit());

        DirectoryServer owner = server;
        CertificateReconciliation reconciliation = reconcileCertificates(
                server,
                entry.certificates(),
                now,
                OwnerType.SERVER,
                displayNameOf(server),
                server.getServerPocDisplay(),
                owner::addCertificate,
                owner::removeCertificate);

        server.markSynced(now);
        server.refreshCertificateSummary();
        serverRepository.save(server);

        return UpsertOutcome.of(
                created, reconciliation.cached(), reconciliation.removed(), reconciliation.alertsRaised());
    }

    /**
     * Brings the cached certificates in line with what the directory currently publishes:
     * new fingerprints are parsed and cached, fingerprints that have gone are dropped, and
     * the ones that remain have their expiry state refreshed.
     */
    private CertificateReconciliation reconcileCertificates(
            DirectoryEntry entity,
            List<byte[]> published,
            Instant now,
            OwnerType ownerType,
            String ownerName,
            String contact,
            Consumer<CachedCertificate> add,
            Consumer<CachedCertificate> remove) {

        Map<String, CachedCertificate> parsed = parseAll(published, now, entity.getDn());
        Map<String, CachedCertificate> existing = new LinkedHashMap<>();
        entity.getCertificates().forEach(certificate -> existing.put(certificate.getSha256Fingerprint(), certificate));

        int cached = 0;
        int removed = 0;
        int alertsRaised = 0;

        for (CachedCertificate certificate : new ArrayList<>(entity.getCertificates())) {
            if (!parsed.containsKey(certificate.getSha256Fingerprint())) {
                remove.accept(certificate);
                removed++;
            }
        }

        for (Map.Entry<String, CachedCertificate> candidate : parsed.entrySet()) {
            CachedCertificate certificate = existing.get(candidate.getKey());
            boolean isNew = certificate == null;
            if (isNew) {
                certificate = candidate.getValue();
                add.accept(certificate);
                cached++;
            }

            CertificateStatus previous = certificate.getStatus();
            CertificateStatus current = evaluator.evaluate(certificate.getNotAfter(), now);
            certificate.updateStatus(current, now);

            // A newly discovered certificate is not news, it is just the first time we
            // looked. Only a transition into a bad state is worth waking someone for.
            if (!isNew && current != previous && current.isAlertable()) {
                long daysUntilExpiry = evaluator.daysUntilExpiry(certificate.getNotAfter(), now);
                alertDispatcher.dispatch(CertificateAlert.from(
                        ownerType,
                        ownerName,
                        entity.getDn(),
                        contact,
                        certificate,
                        evaluator.severityFor(current, daysUntilExpiry),
                        daysUntilExpiry,
                        now));
                alertsRaised++;
            }
        }

        return new CertificateReconciliation(cached, removed, alertsRaised);
    }

    private Map<String, CachedCertificate> parseAll(List<byte[]> published, Instant now, String dn) {
        Map<String, CachedCertificate> parsed = new LinkedHashMap<>();
        for (byte[] der : published) {
            try {
                CachedCertificate certificate = certificateParser.parse(der, now);
                parsed.putIfAbsent(certificate.getSha256Fingerprint(), certificate);
            } catch (CertificateParseException e) {
                // One unreadable attribute value should not cost us the rest of the entry.
                log.warn("Skipping unreadable certificate on entry '{}': {}", dn, e.getMessage());
            }
        }
        return parsed;
    }

    private String displayNameOf(DirectoryUser user) {
        if (user.getDisplayName() != null) {
            return user.getDisplayName();
        }
        if (user.getCommonName() != null) {
            return user.getCommonName();
        }
        return user.getUid() != null ? user.getUid() : user.getDn();
    }

    private String displayNameOf(DirectoryServer server) {
        if (server.getCommonName() != null) {
            return server.getCommonName();
        }
        return server.getFqdn() != null ? server.getFqdn() : server.getDn();
    }

    private record CertificateReconciliation(int cached, int removed, int alertsRaised) {}
}

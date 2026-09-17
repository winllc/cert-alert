package com.winllc.certalert.service;

import com.winllc.certalert.alert.AlertDispatcher;
import com.winllc.certalert.alert.CertificateAlert;
import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryEntry;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.ldap.LdapProperties;
import com.winllc.certalert.ldap.LdapServerEntry;
import com.winllc.certalert.ldap.LdapUserEntry;
import com.winllc.certalert.ldap.ServerField;
import com.winllc.certalert.ldap.UserField;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.ServerContactRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles what the directory says with what is already cached.
 *
 * <p>Work is done a batch at a time rather than an entry at a time. A batch costs one
 * query to load the entries already held, then one flush; doing it per entry would cost
 * two round trips each, which across 100,000 entries is the difference between a sync
 * measured in seconds and one measured in hours. The persistence context is cleared after
 * every batch so it never grows to hold the whole directory.
 */
@Service
public class DirectoryPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(DirectoryPersistenceService.class);

    private final DirectoryUserRepository userRepository;
    private final DirectoryServerRepository serverRepository;
    private final CertificateParser certificateParser;
    private final CertificateStatusEvaluator evaluator;
    private final AlertDispatcher alertDispatcher;
    private final LdapProperties ldapProperties;
    private final ServerContactRepository contactRepository;
    private final AuditService auditService;

    @PersistenceContext
    private EntityManager entityManager;

    public DirectoryPersistenceService(
            DirectoryUserRepository userRepository,
            DirectoryServerRepository serverRepository,
            CertificateParser certificateParser,
            CertificateStatusEvaluator evaluator,
            AlertDispatcher alertDispatcher,
            LdapProperties ldapProperties,
            ServerContactRepository contactRepository,
            AuditService auditService) {
        this.userRepository = userRepository;
        this.serverRepository = serverRepository;
        this.certificateParser = certificateParser;
        this.evaluator = evaluator;
        this.alertDispatcher = alertDispatcher;
        this.ldapProperties = ldapProperties;
        this.contactRepository = contactRepository;
        this.auditService = auditService;
    }

    @Transactional
    public BatchOutcome upsertUsers(List<LdapUserEntry> batch, Instant now, String actor) {
        if (batch.isEmpty()) {
            return BatchOutcome.EMPTY;
        }
        Map<String, DirectoryUser> existing = index(userRepository.findAllByDnIn(dns(batch, LdapUserEntry::dn)));
        BatchOutcome outcome = BatchOutcome.EMPTY;
        List<DirectoryUser> toSave = new ArrayList<>(batch.size());
        List<Change> changes = new ArrayList<>();

        for (LdapUserEntry entry : batch) {
            DirectoryUser user = existing.get(entry.dn());
            boolean created = user == null;
            if (created) {
                user = new DirectoryUser(entry.dn());
            }
            applyAttributes(user, entry);

            DirectoryUser owner = user;
            CertificateReconciliation reconciliation = reconcileCertificates(
                    user,
                    entry.certificates(),
                    now,
                    OwnerType.USER,
                    displayNameOf(user),
                    user.getId(),
                    created,
                    owner::getEmail,
                    owner::addCertificate,
                    owner::removeCertificate,
                    changes);

            user.markSynced(now);
            user.refreshCertificateSummary();
            toSave.add(user);
            if (created) {
                changes.add(new Change(user, AuditAction.ENTRY_DISCOVERED, discovered(entry.certificates()), null));
            }
            outcome = outcome.plus(new BatchOutcome(
                    created ? 1 : 0,
                    reconciliation.cached(),
                    reconciliation.removed(),
                    reconciliation.alertsRaised()));
        }

        userRepository.saveAll(toSave);
        recordChanges(changes, now, actor);
        flushAndClear();
        return outcome;
    }

    @Transactional
    public BatchOutcome upsertServers(List<LdapServerEntry> batch, Instant now, String actor) {
        if (batch.isEmpty()) {
            return BatchOutcome.EMPTY;
        }
        Map<String, DirectoryServer> existing =
                index(serverRepository.findAllByDnIn(dns(batch, LdapServerEntry::dn)));
        BatchOutcome outcome = BatchOutcome.EMPTY;
        List<DirectoryServer> toSave = new ArrayList<>(batch.size());
        List<Change> changes = new ArrayList<>();

        for (LdapServerEntry entry : batch) {
            DirectoryServer server = existing.get(entry.dn());
            boolean created = server == null;
            if (created) {
                server = new DirectoryServer(entry.dn());
            }
            applyAttributes(server, entry);

            DirectoryServer owner = server;
            CertificateReconciliation reconciliation = reconcileCertificates(
                    server,
                    entry.certificates(),
                    now,
                    OwnerType.SERVER,
                    displayNameOf(server),
                    server.getId(),
                    created,
                    () -> contactsFor(owner),
                    owner::addCertificate,
                    owner::removeCertificate,
                    changes);

            server.markSynced(now);
            server.refreshCertificateSummary();
            toSave.add(server);
            if (created) {
                changes.add(new Change(server, AuditAction.ENTRY_DISCOVERED, discovered(entry.certificates()), null));
            }
            outcome = outcome.plus(new BatchOutcome(
                    created ? 1 : 0,
                    reconciliation.cached(),
                    reconciliation.removed(),
                    reconciliation.alertsRaised()));
        }

        serverRepository.saveAll(toSave);
        recordChanges(changes, now, actor);
        flushAndClear();
        return outcome;
    }

    private void applyAttributes(DirectoryUser user, LdapUserEntry entry) {
        user.setUid(entry.get(UserField.UID));
        user.setCommonName(entry.get(UserField.COMMON_NAME));
        user.setDisplayName(entry.get(UserField.DISPLAY_NAME));
        user.setPreferredName(entry.get(UserField.PREFERRED_NAME));
        user.setGivenName(entry.get(UserField.GIVEN_NAME));
        user.setSurname(entry.get(UserField.SURNAME));
        user.setMail(entry.get(UserField.MAIL));
        user.setIcEmail(entry.get(UserField.IC_EMAIL));
        user.setInternetEmail(entry.get(UserField.INTERNET_EMAIL));
        user.setNiprnetEmail(entry.get(UserField.NIPRNET_EMAIL));
        user.setSiprnetEmail(entry.get(UserField.SIPRNET_EMAIL));
        user.setTelephoneNumber(entry.get(UserField.TELEPHONE_NUMBER));
        user.setTitle(entry.get(UserField.TITLE));
        user.setEmployeeType(entry.get(UserField.EMPLOYEE_TYPE));
        user.setRank(entry.get(UserField.RANK));
        user.setCountryOfAffiliation(entry.get(UserField.COUNTRY_OF_AFFILIATION));
        user.setDutyOrganization(entry.get(UserField.DUTY_ORGANIZATION));
        user.setAdminOrganization(entry.get(UserField.ADMIN_ORGANIZATION));
        user.setIcMember(parseBoolean(entry.get(UserField.IS_IC_MEMBER)));
        user.setIcNetworks(entry.get(UserField.IC_NETWORKS));
        user.setResourceSecurityMark(entry.get(UserField.RESOURCE_SECURITY_MARK));
        user.setOrganization(entry.get(UserField.ORGANIZATION));
        user.setOrganizationalUnit(entry.get(UserField.ORGANIZATIONAL_UNIT));
        user.refreshIdentifiers(primaryEmail(entry));
    }

    private void applyAttributes(DirectoryServer server, LdapServerEntry entry) {
        server.setCommonName(entry.get(ServerField.COMMON_NAME));
        server.setUid(entry.get(ServerField.UID));
        server.setGivenName(entry.get(ServerField.GIVEN_NAME));
        server.setDescription(entry.get(ServerField.DESCRIPTION));
        server.setServerUrl(entry.get(ServerField.SERVER_URL));
        server.setIcServerAddress(entry.get(ServerField.IC_SERVER_ADDRESS));
        server.setAtoStatus(entry.get(ServerField.ATO_STATUS));
        server.setLifeCycleStatus(entry.get(ServerField.LIFE_CYCLE_STATUS));
        server.setEmployeeType(entry.get(ServerField.EMPLOYEE_TYPE));
        server.setCountryOfAffiliation(entry.get(ServerField.COUNTRY_OF_AFFILIATION));
        server.setDutyOrganization(entry.get(ServerField.DUTY_ORGANIZATION));
        server.setAdminOrganization(entry.get(ServerField.ADMIN_ORGANIZATION));
        server.setIcMember(parseBoolean(entry.get(ServerField.IS_IC_MEMBER)));
        server.setIcNetworks(entry.get(ServerField.IC_NETWORKS));
        server.setResourceSecurityMark(entry.get(ServerField.RESOURCE_SECURITY_MARK));
        server.setOrganization(entry.get(ServerField.ORGANIZATION));
        server.setOrganizationalUnit(entry.get(ServerField.ORGANIZATIONAL_UNIT));
        server.setServerPocs(entry.serverPocs());
    }

    /**
     * Something that happened to an entry during a batch, held until the batch is written.
     *
     * <p>An entry seen for the first time has no id until it is saved, and an audit record
     * pointing at no entry is not one anybody can read. So the records are built after the
     * batch is written rather than as the changes are noticed.
     */
    private record Change(DirectoryEntry entity, AuditAction action, String summary, String fingerprint) {}

    private void recordChanges(List<Change> changes, Instant now, String actor) {
        if (changes.isEmpty()) {
            return;
        }
        auditService.recordAll(changes.stream()
                .map(change -> AuditEvent.about(subjectOf(change.entity()), change.action(), change.summary(), now)
                        .by(actor)
                        .forCertificate(change.fingerprint()))
                .toList());
    }

    private AuditEvent.SubjectRef subjectOf(DirectoryEntry entity) {
        return entity instanceof DirectoryUser user
                ? AuditEvent.SubjectRef.of(user)
                : AuditEvent.SubjectRef.of((DirectoryServer) entity);
    }

    private String discovered(List<byte[]> certificates) {
        return switch (certificates.size()) {
            case 0 -> "First seen in the directory, publishing no certificate";
            case 1 -> "First seen in the directory, publishing 1 certificate";
            default -> "First seen in the directory, publishing %d certificates".formatted(certificates.size());
        };
    }

    /**
     * Who to chase about a server: the contacts the directory publishes, and the ones added
     * here, which are the whole reason somebody adds one.
     *
     * <p>Resolved only when an alert is being raised - a transition into a bad state, which
     * is rare - rather than on every server in every batch. A sweep of a hundred thousand
     * servers that raises four alerts issues four of these queries.
     */
    private String contactsFor(DirectoryServer server) {
        List<String> contacts = new ArrayList<>();
        if (server.getServerPocDisplay() != null && !server.getServerPocDisplay().isBlank()) {
            contacts.add(server.getServerPocDisplay());
        }
        if (server.getId() != null) {
            contactRepository.findAddressesByServerId(server.getId()).stream()
                    .filter(address -> address != null && !address.isBlank())
                    .filter(address -> !contacts.contains(address))
                    .forEach(contacts::add);
        }
        return contacts.isEmpty() ? null : String.join(", ", contacts);
    }

    /**
     * Picks the address shown as primary. A person may hold four network addresses at
     * once, so which one leads is a policy decision, not a fact about the entry.
     */
    private String primaryEmail(LdapUserEntry entry) {
        for (String name : ldapProperties.getUser().getEmailPrecedence()) {
            UserField field = UserField.byPropertyName(name);
            if (field != null) {
                String value = entry.get(field);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    /** LDAP booleans are the strings TRUE and FALSE; be forgiving about the rest. */
    private Boolean parseBoolean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalised = value.trim();
        if (normalised.equalsIgnoreCase("true") || normalised.equals("1") || normalised.equalsIgnoreCase("yes")) {
            return Boolean.TRUE;
        }
        if (normalised.equalsIgnoreCase("false") || normalised.equals("0") || normalised.equalsIgnoreCase("no")) {
            return Boolean.FALSE;
        }
        return null;
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
            Long ownerId,
            boolean created,
            Supplier<String> contact,
            Consumer<CachedCertificate> add,
            Consumer<CachedCertificate> remove,
            List<Change> changes) {

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
                changes.add(new Change(
                        entity,
                        AuditAction.CERTIFICATE_REMOVED,
                        "The directory stopped publishing the certificate for %s, expiring %s"
                                .formatted(certificate.getSubjectDn(), certificate.getNotAfter()),
                        certificate.getSha256Fingerprint()));
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

            // A certificate that arrived with an entry nobody had seen before is what the
            // entry is, not something that changed about it; the discovery covers it.
            if (isNew && !created) {
                changes.add(new Change(
                        entity,
                        AuditAction.CERTIFICATE_CACHED,
                        "The directory published a certificate for %s, expiring %s"
                                .formatted(certificate.getSubjectDn(), certificate.getNotAfter()),
                        certificate.getSha256Fingerprint()));
            }

            CertificateStatus previous = certificate.getStatus();
            CertificateStatus current = evaluator.evaluate(certificate.getNotAfter(), now);
            certificate.updateStatus(current, now);

            if (!isNew && current != previous) {
                changes.add(new Change(
                        entity,
                        AuditAction.CERTIFICATE_STATUS_CHANGED,
                        "The certificate for %s went from %s to %s"
                                .formatted(certificate.getSubjectDn(), previous, current),
                        certificate.getSha256Fingerprint()));
            }

            // A newly discovered certificate is not news, it is just the first time we
            // looked. Only a transition into a bad state is worth waking someone for.
            if (!isNew && current != previous && current.isAlertable()) {
                long daysUntilExpiry = evaluator.daysUntilExpiry(certificate.getNotAfter(), now);
                alertDispatcher.dispatch(CertificateAlert.from(
                        ownerType,
                        ownerId,
                        ownerName,
                        entity.getDn(),
                        contact.get(),
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

    private <T> List<String> dns(List<T> batch, java.util.function.Function<T, String> dn) {
        return batch.stream().map(dn).toList();
    }

    private <T extends DirectoryEntry> Map<String, T> index(List<T> entries) {
        Map<String, T> byDn = new HashMap<>();
        entries.forEach(entry -> byDn.put(entry.getDn(), entry));
        return byDn;
    }

    /** Keeps the session from growing to hold the whole directory. */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
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
        return server.getServerUrl() != null ? server.getServerUrl() : server.getDn();
    }

    private record CertificateReconciliation(int cached, int removed, int alertsRaised) {}
}

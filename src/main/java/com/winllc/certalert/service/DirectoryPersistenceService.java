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
import com.winllc.certalert.domain.Severity;
import com.winllc.certalert.ldap.LdapProperties;
import com.winllc.certalert.license.Licensing;
import com.winllc.certalert.ldap.LdapServerEntry;
import com.winllc.certalert.ldap.LdapUserEntry;
import com.winllc.certalert.ldap.ServerField;
import com.winllc.certalert.ldap.UserField;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.ServerContactRepository;
import com.winllc.certalert.repository.UserEmailAliasRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
    private final UserEmailAliasRepository aliasRepository;
    private final AuditService auditService;
    private final Licensing licensing;

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
            UserEmailAliasRepository aliasRepository,
            AuditService auditService,
            Licensing licensing) {
        this.userRepository = userRepository;
        this.serverRepository = serverRepository;
        this.certificateParser = certificateParser;
        this.evaluator = evaluator;
        this.alertDispatcher = alertDispatcher;
        this.ldapProperties = ldapProperties;
        this.contactRepository = contactRepository;
        this.aliasRepository = aliasRepository;
        this.auditService = auditService;
        this.licensing = licensing;
    }

    @Transactional
    public BatchOutcome upsertUsers(List<LdapUserEntry> batch, Instant now, String actor) {
        if (batch.isEmpty()) {
            return BatchOutcome.EMPTY;
        }
        batch = firstPerDn(batch, LdapUserEntry::dn);
        Map<String, DirectoryUser> existing = index(userRepository.findAllByDnIn(dns(batch, LdapUserEntry::dn)));
        // One query for the batch: a sweep rebuilds each person's identifiers from the
        // directory, and the addresses added here have to go back in or they would last
        // only until the next sweep. Most people have none, and this costs one query per
        // two hundred either way.
        Map<Long, List<String>> aliases = aliasesFor(existing.values());
        BatchOutcome outcome = BatchOutcome.EMPTY;
        List<DirectoryUser> toSave = new ArrayList<>(batch.size());
        List<Change> changes = new ArrayList<>();
        // The license is sized in directory users: somebody new is added only while it
        // allows one more. Nobody already here is ever removed for it. Counted only when
        // the batch holds somebody new, which after the first sweep is rare.
        long people = batch.stream().anyMatch(entry -> !existing.containsKey(entry.dn()))
                ? userRepository.count()
                : 0;

        for (LdapUserEntry entry : batch) {
            DirectoryUser user = existing.get(entry.dn());
            boolean created = user == null;
            if (created) {
                if (!licensing.mayAdd(people)) {
                    outcome = outcome.plus(new BatchOutcome(0, 0, 0, 0, 1));
                    continue;
                }
                people++;
                user = new DirectoryUser(entry.dn());
            }
            // An entry nobody has seen before has no id yet, and so no addresses of its own.
            applyAttributes(user, entry, created ? List.of() : aliases.getOrDefault(user.getId(), List.of()));

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
        batch = firstPerDn(batch, LdapServerEntry::dn);
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

    private Map<Long, List<String>> aliasesFor(Collection<DirectoryUser> users) {
        List<Long> ids = users.stream().map(DirectoryUser::getId).filter(java.util.Objects::nonNull).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> byUser = new LinkedHashMap<>();
        for (UserEmailAliasRepository.AliasAddress alias : aliasRepository.findAddressesByUserIdIn(ids)) {
            byUser.computeIfAbsent(alias.getUserId(), key -> new ArrayList<>()).add(alias.getAddress());
        }
        return byUser;
    }

    private void applyAttributes(DirectoryUser user, LdapUserEntry entry, List<String> aliases) {
        user.setUid(clip(entry.get(UserField.UID), 255));
        user.setCommonName(clip(entry.get(UserField.COMMON_NAME), 255));
        user.setDisplayName(clip(entry.get(UserField.DISPLAY_NAME), 255));
        user.setPreferredName(clip(entry.get(UserField.PREFERRED_NAME), 255));
        user.setGivenName(clip(entry.get(UserField.GIVEN_NAME), 255));
        user.setSurname(clip(entry.get(UserField.SURNAME), 255));
        user.setMail(clip(entry.get(UserField.MAIL), 320));
        user.setIcEmail(clip(entry.get(UserField.IC_EMAIL), 320));
        user.setInternetEmail(clip(entry.get(UserField.INTERNET_EMAIL), 320));
        user.setNiprnetEmail(clip(entry.get(UserField.NIPRNET_EMAIL), 320));
        user.setSiprnetEmail(clip(entry.get(UserField.SIPRNET_EMAIL), 320));
        user.setTelephoneNumber(clip(entry.get(UserField.TELEPHONE_NUMBER), 64));
        user.setTitle(clip(entry.get(UserField.TITLE), 255));
        user.setEmployeeType(clip(entry.get(UserField.EMPLOYEE_TYPE), 128));
        user.setRank(clip(entry.get(UserField.RANK), 128));
        user.setCountryOfAffiliation(clip(entry.get(UserField.COUNTRY_OF_AFFILIATION), 128));
        user.setDutyOrganization(clip(entry.get(UserField.DUTY_ORGANIZATION), 255));
        user.setDutySubOrganization(clip(entry.get(UserField.DUTY_SUB_ORGANIZATION), 255));
        user.setAdminOrganization(clip(entry.get(UserField.ADMIN_ORGANIZATION), 255));
        user.setIcMember(parseBoolean(entry.get(UserField.IS_IC_MEMBER)));
        user.setIcNetworks(clip(entry.get(UserField.IC_NETWORKS), 500));
        user.setResourceSecurityMark(clip(entry.get(UserField.RESOURCE_SECURITY_MARK), 500));
        user.setOrganization(clip(entry.get(UserField.ORGANIZATION), 255));
        user.setOrganizationalUnit(clip(entry.get(UserField.ORGANIZATIONAL_UNIT), 255));
        // Before the identifiers are rebuilt, because they are rebuilt from these.
        user.setAdditionalEmails(addresses(entry.allExtraEmails()));
        user.refreshIdentifiers(clip(primaryEmail(entry), ADDRESS), aliases);
    }

    private void applyAttributes(DirectoryServer server, LdapServerEntry entry) {
        server.setCommonName(clip(entry.get(ServerField.COMMON_NAME), 255));
        server.setUid(clip(entry.get(ServerField.UID), 255));
        server.setGivenName(clip(entry.get(ServerField.GIVEN_NAME), 255));
        server.setDescription(clip(entry.get(ServerField.DESCRIPTION), 1000));
        server.setServerUrl(clip(entry.get(ServerField.SERVER_URL), 500));
        server.setIcServerAddress(clip(entry.get(ServerField.IC_SERVER_ADDRESS), 128));
        server.setAtoStatus(clip(entry.get(ServerField.ATO_STATUS), 128));
        server.setLifeCycleStatus(clip(entry.get(ServerField.LIFE_CYCLE_STATUS), 128));
        server.setEmployeeType(clip(entry.get(ServerField.EMPLOYEE_TYPE), 128));
        server.setCountryOfAffiliation(clip(entry.get(ServerField.COUNTRY_OF_AFFILIATION), 128));
        server.setDutyOrganization(clip(entry.get(ServerField.DUTY_ORGANIZATION), 255));
        server.setDutySubOrganization(clip(entry.get(ServerField.DUTY_SUB_ORGANIZATION), 255));
        server.setAdminOrganization(clip(entry.get(ServerField.ADMIN_ORGANIZATION), 255));
        server.setIcMember(parseBoolean(entry.get(ServerField.IS_IC_MEMBER)));
        server.setIcNetworks(clip(entry.get(ServerField.IC_NETWORKS), 500));
        server.setResourceSecurityMark(clip(entry.get(ServerField.RESOURCE_SECURITY_MARK), 500));
        server.setOrganization(clip(entry.get(ServerField.ORGANIZATION), 255));
        server.setOrganizationalUnit(clip(entry.get(ServerField.ORGANIZATIONAL_UNIT), 255));
        server.setServerPocs(addresses(entry.serverPocs()));
    }

    /** The longest address column; an address longer than this is not one. */
    private static final int ADDRESS = 320;

    /**
     * A value cut to fit its column. The directory's schema is not this one's, and one entry
     * with an over-long title would otherwise fail its whole batch on every sweep.
     */
    static String clip(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }

    /** Addresses that fit; a longer value is no address, and cutting it would make it a wrong one. */
    private static List<String> addresses(Collection<String> values) {
        return values == null
                ? List.of()
                : values.stream().filter(value -> value == null || value.length() <= ADDRESS).toList();
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
        return contactsFor(server, contactRepository);
    }

    /**
     * The same answer for the hourly refresh, which notices most expiries - a certificate
     * crosses into a bad state because time passed far more often than because the
     * directory changed - and has to name the same people.
     */
    static String contactsFor(DirectoryServer server, ServerContactRepository contactRepository) {
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
     *
     * <p>A name in the precedence list is either one of the schema's fields or one of the
     * attributes this deployment named for itself - a deployment that reads addresses out
     * of its own attribute usually means that one to lead, and having to keep a second
     * setting in step to say so would be a way of getting it wrong.
     */
    private String primaryEmail(LdapUserEntry entry) {
        for (String name : ldapProperties.getUser().getEmailPrecedence()) {
            UserField field = UserField.byPropertyName(name);
            if (field != null) {
                String value = entry.get(field);
                if (value != null && !value.isBlank()) {
                    return value;
                }
                continue;
            }
            List<String> extra = entry.extraEmails(name);
            if (!extra.isEmpty()) {
                return extra.getFirst();
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

        List<CachedCertificate> alertable = new ArrayList<>();
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
                alertable.add(certificate);
            }
        }

        // With everything the entry now publishes in place: an old certificate lapsing
        // beside its renewal is in the history above, but nobody is woken for it.
        for (CachedCertificate certificate : alertable) {
            if (Renewals.replaced(certificate, entity.getCertificates())) {
                continue;
            }
            CertificateStatus current = certificate.getStatus();
            long daysUntilExpiry = evaluator.daysUntilExpiry(certificate.getNotAfter(), now);
            Severity severity = evaluator.severityFor(current, daysUntilExpiry);
            certificate.alerted(severity);
            alertDispatcher.dispatch(CertificateAlert.from(
                    ownerType,
                    ownerId,
                    ownerName,
                    entity.getDn(),
                    contact.get(),
                    certificate,
                    severity,
                    daysUntilExpiry,
                    now));
            alertsRaised++;
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

    /**
     * The batch with each DN once. The same DN twice would be two new rows for one entry,
     * and the unique constraint would fail the batch.
     */
    private static <T> List<T> firstPerDn(List<T> batch, java.util.function.Function<T, String> dn) {
        Map<String, T> byDn = new LinkedHashMap<>();
        batch.forEach(entry -> byDn.putIfAbsent(dn.apply(entry), entry));
        return byDn.size() == batch.size() ? batch : List.copyOf(byDn.values());
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

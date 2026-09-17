package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.repository.AuditEventRepository;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestCertificates;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What the audit trail records, and what it deliberately does not.
 *
 * <p>Driven through the real sweep against a real directory, because the interesting cases
 * are all about the difference between a change and a sighting.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditTrailTest {

    private static EmbeddedDirectory directory;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private DirectoryPruneService pruneService;

    @Autowired
    private ServerContactService contactService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private AuditEventRepository auditRepository;

    @Autowired
    private DirectoryUserRepository userRepository;

    @Autowired
    private DirectoryServerRepository serverRepository;

    @BeforeAll
    static void startDirectory() {
        directory = new EmbeddedDirectory();
    }

    @AfterAll
    static void stopDirectory() {
        if (directory != null) {
            directory.close();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", () -> directory.url());
        registry.add("spring.ldap.base", () -> EmbeddedDirectory.BASE_DN);
        registry.add("cert-alert.ldap.user.search-base", () -> "ou=people");
        registry.add("cert-alert.ldap.server.search-base", () -> "ou=servers");
        // So a prune can be driven without waiting a month for the window to pass.
        registry.add("cert-alert.ldap.prune.after", () -> "0s");
    }

    @BeforeEach
    void reset() {
        auditRepository.deleteAll();
        serverRepository.deleteAll();
        userRepository.deleteAll();
    }

    /**
     * The first sweep to see an entry records that, once, however many certificates it
     * arrived with. Those certificates are what the entry <em>is</em>; nothing about them
     * changed, and recording one per certificate would bury the changes that follow under
     * a hundred thousand rows of "we looked".
     */
    @Test
    void discoveringAnEntryIsOneRecordWhateverItArrivedWith() {
        String dn = directory.addUser(
                "discovered", "Dee Scovered", "dee@example.gov",
                TestCertificates.expiringIn("dee-a", Duration.ofDays(300)),
                TestCertificates.expiringIn("dee-b", Duration.ofDays(400)));

        syncService.syncUsers();

        List<AuditEvent> history = historyOf(OwnerType.USER, userIdOf(dn));
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().getAction()).isEqualTo(AuditAction.ENTRY_DISCOVERED);
        assertThat(history.getFirst().getSummary()).contains("publishing 2 certificates");
        assertThat(history.getFirst().getActor()).isEqualTo(AuditActors.SYNC);
        assertThat(history.getFirst().getSubjectDn()).isEqualTo(dn);
        assertThat(history.getFirst().getSubjectName()).isEqualTo("Dee Scovered");
    }

    /** A sweep that finds nothing changed writes nothing. */
    @Test
    void anUneventfulSweepRecordsNothing() {
        directory.addUser("quiet", "Quiet Holder", "quiet@example.gov",
                TestCertificates.expiringIn("quiet", Duration.ofDays(300)));
        syncService.syncUsers();
        long afterFirst = auditRepository.count();

        syncService.syncUsers();

        assertThat(auditRepository.count()).isEqualTo(afterFirst);
    }

    @Test
    void publishingAndWithdrawingACertificateAreBothRecorded() {
        byte[] first = TestCertificates.expiringIn("changing-a", Duration.ofDays(300));
        String dn = directory.addUser("changing", "Chan Ging", "chan@example.gov", first);
        syncService.syncUsers();

        directory.replaceCertificates(dn, TestCertificates.expiringIn("changing-b", Duration.ofDays(500)));
        syncService.syncUsers();

        List<AuditEvent> history = historyOf(OwnerType.USER, userIdOf(dn));
        assertThat(history).extracting(AuditEvent::getAction)
                .containsExactlyInAnyOrder(
                        AuditAction.ENTRY_DISCOVERED,
                        AuditAction.CERTIFICATE_CACHED,
                        AuditAction.CERTIFICATE_REMOVED);
        // Each names the certificate it is about, so one can be followed through the trail.
        assertThat(history.stream()
                        .filter(event -> event.getAction() != AuditAction.ENTRY_DISCOVERED)
                        .map(AuditEvent::getCertificateFingerprint))
                .doesNotContainNull()
                .doesNotHaveDuplicates();
    }

    /**
     * A certificate crossing into expiry is two records: the state change, and every
     * attempt to tell somebody about it.
     */
    @Test
    void expiryRecordsTheChangeAndTheNotification() throws InterruptedException {
        String dn = directory.addUser("expiring", "Ex Piring", "ex@example.gov",
                TestCertificates.expiringIn("expiring", Duration.ofSeconds(2)));
        syncService.syncUsers();
        Thread.sleep(Duration.ofSeconds(3).toMillis());

        syncService.syncUsers();

        List<AuditEvent> history = historyOf(OwnerType.USER, userIdOf(dn));
        assertThat(history).extracting(AuditEvent::getAction)
                .contains(AuditAction.CERTIFICATE_STATUS_CHANGED, AuditAction.ALERT_SENT);

        AuditEvent change = eventOf(history, AuditAction.CERTIFICATE_STATUS_CHANGED);
        assertThat(change.getSummary()).contains("EXPIRING_SOON").contains("EXPIRED");

        // The delivery names the channel that took it and where it went. The summary is the
        // alert itself: the action and the channel are recorded as such, not restated.
        AuditEvent sent = eventOf(history, AuditAction.ALERT_SENT);
        assertThat(sent.getSummary()).contains("Ex Piring").doesNotContain("alert sent via");
        assertThat(sent.getChannel()).isNotBlank();
        assertThat(sent.getTarget()).isEqualTo("ex@example.gov");
        assertThat(sent.getCertificateFingerprint()).isEqualTo(change.getCertificateFingerprint());
    }

    @Test
    void editingThePointsOfContactIsRecordedAgainstTheServer() {
        String dn = directory.addServer("contacted", "https://contacted.example.gov", new String[] {"ops@x.gov"});
        syncService.syncServers();
        Long serverId = serverRepository.findByDn(dn).orElseThrow().getId();

        var contact = contactService.addEmail(serverId, "duty.desk@example.gov", "alice");
        contactService.remove(serverId, contact.getId());

        List<AuditEvent> history = historyOf(OwnerType.SERVER, serverId);
        assertThat(history).extracting(AuditEvent::getAction)
                .containsExactly(AuditAction.CONTACT_REMOVED, AuditAction.CONTACT_ADDED, AuditAction.ENTRY_DISCOVERED);

        AuditEvent added = eventOf(history, AuditAction.CONTACT_ADDED);
        assertThat(added.getActor()).isEqualTo("alice");
        assertThat(added.getTarget()).isEqualTo("duty.desk@example.gov");
        assertThat(added.getSummary()).contains("duty.desk@example.gov");
    }

    /**
     * The record of a deletion is the one an audit trail exists for, so it has to outlive
     * what it describes - which is why the subject is not a foreign key.
     */
    @Test
    void aPrunedEntryLeavesItsHistoryBehind() {
        String dn = directory.addUser("doomed", "Doo Med", "doomed@example.gov");
        syncService.syncUsers();
        Long userId = userIdOf(dn);

        pruneService.prune();

        assertThat(userRepository.findByDn(dn)).isEmpty();
        List<AuditEvent> history = historyOf(OwnerType.USER, userId);
        assertThat(history).extracting(AuditEvent::getAction)
                .containsExactly(AuditAction.ENTRY_PRUNED, AuditAction.ENTRY_DISCOVERED);

        AuditEvent pruned = eventOf(history, AuditAction.ENTRY_PRUNED);
        assertThat(pruned.getActor()).isEqualTo(AuditActors.PRUNE);
        // Still readable with nothing left to join to.
        assertThat(pruned.getSubjectDn()).isEqualTo(dn);
        assertThat(pruned.getSubjectName()).isEqualTo("Doo Med");
    }

    /** Retention is off unless it is turned on, whatever the window says. */
    @Test
    void nothingIsTrimmedUntilRetentionIsTurnedOn() {
        directory.addUser("retained", "Re Tained", "retained@example.gov");
        syncService.syncUsers();
        long before = auditRepository.count();
        assertThat(before).isPositive();

        assertThat(auditService.trim()).isZero();
        assertThat(auditRepository.count()).isEqualTo(before);
    }

    private List<AuditEvent> historyOf(OwnerType type, Long id) {
        return auditService.history(type, id, PageRequest.of(0, 50)).getContent();
    }

    private AuditEvent eventOf(List<AuditEvent> history, AuditAction action) {
        return history.stream()
                .filter(event -> event.getAction() == action)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + action + " in " + history));
    }

    private Long userIdOf(String dn) {
        return userRepository.findByDn(dn).map(DirectoryUser::getId).orElseThrow();
    }
}

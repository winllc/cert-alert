package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.winllc.certalert.alert.AlertDispatcher;
import com.winllc.certalert.alert.CertificateAlert;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.Severity;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.SyncRunRepository;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestCertificates;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drives the whole scrape against a real LDAP server and a real database, with only the
 * alert fan-out stubbed.
 */
@SpringBootTest
@ActiveProfiles("test")
class DirectorySyncServiceTest {

    private static EmbeddedDirectory directory;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private DirectoryUserRepository userRepository;

    @Autowired
    private DirectoryServerRepository serverRepository;

    @MockitoBean
    private AlertDispatcher alertDispatcher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ServerContactService contactService;

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
    static void directoryProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", () -> directory.url());
        registry.add("spring.ldap.base", () -> EmbeddedDirectory.BASE_DN);
        registry.add("cert-alert.ldap.user.search-base", () -> "ou=people");
        registry.add("cert-alert.ldap.server.search-base", () -> "ou=servers");
    }

    @BeforeEach
    void resetDatabase() {
        serverRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void scrapesUsersWithTheirCertificateDetails() {
        directory.addUser(
                "jsmith", "Jane Smith", "Jane.Smith@example.gov", TestCertificates.expiringIn("jsmith", Duration.ofDays(200)));

        DirectorySyncResult result = syncService.syncUsers();

        assertThat(result.entriesSeen()).isPositive();
        DirectoryUser user = userByDn("uid=jsmith," + EmbeddedDirectory.PEOPLE_DN);
        assertThat(user.getUid()).isEqualTo("jsmith");
        assertThat(user.getDisplayName()).isEqualTo("Jane Smith");
        // Stored lowercased, because it is the join key to serverPoc.
        assertThat(user.getEmail()).isEqualTo("jane.smith@example.gov");
        assertThat(user.getIcEmail()).isEqualTo("Jane.Smith@example.gov");
        assertThat(user.getCountryOfAffiliation()).isEqualTo("USA");
        assertThat(user.getIcMember()).isTrue();
        assertThat(user.getDutyOrganization()).isEqualTo("Example Agency");
        assertThat(user.getCertificateCount()).isEqualTo(1);
        assertThat(user.getCertificateStatus()).isEqualTo(CertificateStatus.VALID);

        CachedCertificate certificate = user.getCertificates().getFirst();
        assertThat(certificate.getSubjectDn()).isEqualTo("CN=jsmith");
        assertThat(certificate.getIssuerDn()).isEqualTo("CN=jsmith");
        assertThat(certificate.getKeyAlgorithm()).isEqualTo("RSA");
        assertThat(certificate.getKeySize()).isEqualTo(2048);
        assertThat(certificate.getSignatureAlgorithm()).isEqualToIgnoringCase("SHA256withRSA");
        assertThat(certificate.getSubjectAlternativeNames()).contains("jsmith");
        assertThat(certificate.getSha256Fingerprint()).hasSize(64);
        assertThat(certificate.getNotAfter()).isAfter(certificate.getNotBefore());
    }

    @Test
    void scrapesServersWithTheirPointsOfContact() {
        directory.addServer(
                "web01",
                "https://web01.example.gov",
                new String[] {"Jane.Smith@example.gov", "ops@example.gov"},
                TestCertificates.expiringIn("web01.example.gov", Duration.ofDays(100)));

        syncAll();

        DirectoryServer server = inTransaction(() -> {
            DirectoryServer loaded =
                    serverRepository.findByDn("cn=web01," + EmbeddedDirectory.SERVERS_DN).orElseThrow();
            loaded.getServerPocs().size();
            return loaded;
        });
        assertThat(server.getCommonName()).isEqualTo("web01");
        assertThat(server.getServerUrl()).isEqualTo("https://web01.example.gov");
        assertThat(server.getIcServerAddress()).isEqualTo("10.1.2.3");
        assertThat(server.getAtoStatus()).isEqualTo("Authorized");
        assertThat(server.getLifeCycleStatus()).isEqualTo("Production");
        
        // Multi-valued, and lowercased so the join to a user's email always matches.
        assertThat(server.getServerPocs()).containsExactlyInAnyOrder("jane.smith@example.gov", "ops@example.gov");
        assertThat(server.getServerPocDisplay()).contains("jane.smith@example.gov");
        assertThat(server.getCertificateCount()).isEqualTo(1);
    }

    /**
     * The attribute is multi-valued, but a directory filled in through a form often carries
     * every contact in one value with commas between them. Read whole that value matches
     * nobody - it is not an address and it is not a name - so the server appears to have no
     * contacts at all and nobody hears when its certificate runs out.
     */
    @Test
    void severalContactsInOneServerPocValueAreSplitApart() {
        directory.addServer(
                "web10",
                "https://web10.example.gov",
                new String[] {"Ops.Desk@example.gov, duty@example.gov,night@example.gov"},
                TestCertificates.expiringIn("web10", Duration.ofDays(100)));

        syncAll();

        DirectoryServer server = serverWithPocs("cn=web10," + EmbeddedDirectory.SERVERS_DN);
        // Lowercased, because this is the join key to a person's addresses.
        assertThat(server.getServerPocs())
                .containsExactlyInAnyOrder("ops.desk@example.gov", "duty@example.gov", "night@example.gov");
        assertThat(server.getServerPocDisplay()).contains("duty@example.gov");
    }

    /**
     * And with the directory's convention declared to be addresses, a value that is not one
     * is bad data rather than somebody's name: nothing can be sent to it, so it is not kept.
     */
    @Test
    void andAValueThatIsNotAnAddressIsNotKept() {
        directory.addServer(
                "web11",
                "https://web11.example.gov",
                new String[] {"Duty Officer, ops@example.gov"},
                TestCertificates.expiringIn("web11", Duration.ofDays(100)));

        syncAll();

        DirectoryServer server = serverWithPocs("cn=web11," + EmbeddedDirectory.SERVERS_DN);
        assertThat(server.getServerPocs()).containsExactly("ops@example.gov");
    }

    @Test
    void rollsUpTheStateOfTheCertificatesTheEntryStandsOn() {
        directory.addUser(
                "mixed",
                "Mixed Holder",
                "mixed@example.gov",
                TestCertificates.expiringIn("mixed-valid", Duration.ofDays(300)),
                TestCertificates.expired("mixed-expired", Duration.ofDays(3)));

        syncAll();

        DirectoryUser user = userByDn("uid=mixed," + EmbeddedDirectory.PEOPLE_DN);
        // Both are still published, and both are still listed on the details page.
        assertThat(user.getCertificateCount()).isEqualTo(2);
        assertThat(user.getCertificates())
                .extracting(CachedCertificate::getStatus)
                .containsExactlyInAnyOrder(CertificateStatus.VALID, CertificateStatus.EXPIRED);

        // But the entry is not expired: it holds one that works. A directory does not
        // withdraw the old certificate when a new one is published, so reading this as
        // EXPIRED made a false alarm out of every correct renewal.
        assertThat(user.getCertificateStatus()).isEqualTo(CertificateStatus.VALID);
        // And the dates the tables sort on are the surviving certificate's, not the dead
        // one's - otherwise this entry sits at the top of "expiring soonest" for ever.
        assertThat(user.getEarliestExpiry()).isEqualTo(user.getLatestExpiry());
        assertThat(user.getEarliestExpiry()).isAfter(Instant.now());
    }

    /**
     * The same for a server, which is where this was noticed: one endpoint, one certificate
     * at a time, and a rotation leaves the old one published beside the new.
     */
    @Test
    void aServerThatHasRotatedIsNotExpired() {
        directory.addServer(
                "web09",
                "https://web09.example.gov",
                new String[] {"alice@example.gov"},
                TestCertificates.expired("web09-old", Duration.ofDays(10)),
                TestCertificates.expiringIn("web09-new", Duration.ofDays(350)));

        syncAll();

        DirectoryServer server = serverByDn("cn=web09," + EmbeddedDirectory.SERVERS_DN);
        assertThat(server.getCertificateCount()).isEqualTo(2);
        assertThat(server.getCertificateStatus()).isEqualTo(CertificateStatus.VALID);
    }

    /**
     * A server that has renewed early holds both for a while: the new certificate, good for
     * a year, and the one it replaced, with days left on it. The server is fine - somebody
     * has already done the work - and reporting it as expiring soon is a renewal already
     * made being counted as one outstanding.
     */
    @Test
    void aServerWithOneGoodCertificateIsValidWhateverElseItPublishes() {
        directory.addServer(
                "web12",
                "https://web12.example.gov",
                new String[] {"alice@example.gov"},
                TestCertificates.expiringIn("web12-old", Duration.ofDays(5)),
                TestCertificates.expiringIn("web12-new", Duration.ofDays(365)));

        syncAll();

        DirectoryServer server = serverByDn("cn=web12," + EmbeddedDirectory.SERVERS_DN);
        assertThat(server.getCertificateCount()).isEqualTo(2);
        assertThat(server.getCertificateStatus()).isEqualTo(CertificateStatus.VALID);
        // The dates are that certificate's too, or the row argues with itself: VALID beside
        // a column saying five days, and top of "soonest to expire" for a certificate
        // nothing depends on.
        assertThat(server.getEarliestExpiry()).isEqualTo(server.getLatestExpiry());
        assertThat(server.getEarliestExpiry()).isAfter(Instant.now().plus(Duration.ofDays(300)));
    }

    /** It takes the best of what is there; it does not invent health that is not. */
    @Test
    void andOneWhoseBestCertificateIsRunningOutStillSaysSo() {
        directory.addServer(
                "web13",
                "https://web13.example.gov",
                new String[] {"alice@example.gov"},
                TestCertificates.expired("web13-old", Duration.ofDays(30)),
                TestCertificates.expiringIn("web13-only", Duration.ofDays(5)));

        syncAll();

        DirectoryServer server = serverByDn("cn=web13," + EmbeddedDirectory.SERVERS_DN);
        assertThat(server.getCertificateStatus()).isEqualTo(CertificateStatus.EXPIRING_SOON);
    }

    /** With nothing left standing the entry really has lapsed, and still says so. */
    @Test
    void andOneWhoseCertificatesHaveAllExpiredStillReadsExpired() {
        directory.addUser(
                "lapsed",
                "Lapsed Holder",
                "lapsed@example.gov",
                TestCertificates.expired("lapsed-a", Duration.ofDays(3)),
                TestCertificates.expired("lapsed-b", Duration.ofDays(40)));

        syncAll();

        DirectoryUser user = userByDn("uid=lapsed," + EmbeddedDirectory.PEOPLE_DN);
        assertThat(user.getCertificateStatus()).isEqualTo(CertificateStatus.EXPIRED);
        // Both count again here, so the dates still say when it lapsed.
        assertThat(user.getEarliestExpiry()).isBefore(user.getLatestExpiry());
    }

    /**
     * Two certificates at once, both still good, one running out. That is a pair rather
     * than a rotation, and the half that is running out is exactly what this exists to
     * report - so the sooner date is the one that counts.
     */
    @Test
    void butTwoLiveCertificatesAreBothStillCountedAgainstTheWarningWindow() {
        directory.addUser(
                "pair",
                "Pair Holder",
                "pair@example.gov",
                TestCertificates.expiringIn("pair-signing", Duration.ofDays(5)),
                TestCertificates.expiringIn("pair-encryption", Duration.ofDays(300)));

        syncAll();

        DirectoryUser user = userByDn("uid=pair," + EmbeddedDirectory.PEOPLE_DN);
        assertThat(user.getCertificateStatus()).isEqualTo(CertificateStatus.EXPIRING_SOON);
        assertThat(user.getEarliestExpiry()).isBefore(user.getLatestExpiry());
    }

    @Test
    void classifiesExpiringCertificatesAgainstTheWarningWindow() {
        directory.addUser("soon", "Soon Expiring", "soon@example.gov",
                TestCertificates.expiringIn("soon", Duration.ofDays(10)));

        syncAll();

        DirectoryUser user = userRepository.findByDn("uid=soon," + EmbeddedDirectory.PEOPLE_DN).orElseThrow();
        assertThat(user.getCertificateStatus()).isEqualTo(CertificateStatus.EXPIRING_SOON);
    }

    @Test
    void neitherDiscoveringNorRotatingACertificateRaisesAnAlert() {
        String dn = directory.addUser("rotating", "Rotating Holder", "rotating@example.gov",
                TestCertificates.expiringIn("rotating", Duration.ofDays(300)));

        syncAll();
        // A first sighting is not news, it is just the first time we looked.
        verify(alertDispatcher, never()).dispatch(any());

        // Swapping in a different certificate replaces one first sighting with another:
        // still nothing anyone needs to be woken for.
        directory.replaceCertificates(dn, TestCertificates.expiringIn("rotating", Duration.ofDays(500)));
        DirectorySyncResult result = syncService.syncUsers();

        assertThat(result.certificatesCached()).isEqualTo(1);
        assertThat(result.certificatesRemoved()).isEqualTo(1);
        verify(alertDispatcher, never()).dispatch(any());
    }

    @Test
    void alertsWhenACachedCertificateCrossesIntoExpiry() {
        // Expires in a moment, so the next sync sees the same cached certificate expire.
        String dn = directory.addUser("crossing", "Crossing Holder", "crossing@example.gov",
                TestCertificates.expiringIn("crossing", Duration.ofSeconds(2)));

        syncAll();
        DirectoryUser afterFirst = userRepository.findByDn(dn).orElseThrow();
        assertThat(afterFirst.getCertificateStatus()).isEqualTo(CertificateStatus.EXPIRING_SOON);
        verify(alertDispatcher, never()).dispatch(any());

        await(Duration.ofSeconds(3));
        syncAll();

        ArgumentCaptor<CertificateAlert> alert = ArgumentCaptor.forClass(CertificateAlert.class);
        verify(alertDispatcher).dispatch(alert.capture());
        assertThat(alert.getValue().status()).isEqualTo(CertificateStatus.EXPIRED);
        assertThat(alert.getValue().severity()).isEqualTo(Severity.CRITICAL);
        assertThat(alert.getValue().ownerType()).isEqualTo(OwnerType.USER);
        assertThat(alert.getValue().ownerName()).isEqualTo("Crossing Holder");
        assertThat(alert.getValue().contact()).isEqualTo("crossing@example.gov");
    }

    /**
     * The directory owns serverPOC and a sweep replaces it wholesale. A contact added here
     * is not the directory's to replace, which is the whole reason it lives in its own
     * table rather than among the scraped values.
     */
    @Test
    void aContactAddedHereSurvivesASweepThatRewritesTheDirectorysOwn() {
        String userDn = directory.addUser("poc-keeper", "Poc Keeper", "poc.keeper@example.gov");
        String serverDn = directory.addServer(
                "poc-server", "https://poc-server.example.gov", new String[] {"first@example.gov"});
        syncAll();

        Long serverId = serverRepository.findByDn(serverDn).orElseThrow().getId();
        Long userId = userRepository.findByDn(userDn).orElseThrow().getId();
        contactService.addUser(serverId, userId, "tester");
        contactService.addEmail(serverId, "duty.desk@example.gov", "tester");

        // The directory changes its mind about who the contact is.
        directory.modify(serverDn, "serverPOC", "second@example.gov");
        syncAll();

        DirectoryServer server = inTransaction(() -> {
            DirectoryServer loaded = serverRepository.findByDn(serverDn).orElseThrow();
            loaded.getServerPocs().size();
            return loaded;
        });
        assertThat(server.getServerPocs()).containsExactly("second@example.gov");
        assertThat(contactService.list(serverId)).hasSize(2);
        assertThat(contactService.addressesFor(serverId))
                .containsExactlyInAnyOrder("poc.keeper@example.gov", "duty.desk@example.gov");
    }

    /** An alert about a server goes to the contacts added here as well as the published ones. */
    @Test
    void anAlertNamesTheContactsAddedHereAlongsideTheDirectorys() {
        String serverDn = directory.addServer(
                "alerting-server",
                "https://alerting.example.gov",
                new String[] {"published@example.gov"},
                TestCertificates.expiringIn("alerting.example.gov", Duration.ofSeconds(2)));
        syncAll();
        verify(alertDispatcher, never()).dispatch(any());

        Long serverId = serverRepository.findByDn(serverDn).orElseThrow().getId();
        contactService.addEmail(serverId, "on.call@example.gov", "tester");

        await(Duration.ofSeconds(3));
        syncAll();

        ArgumentCaptor<CertificateAlert> alert = ArgumentCaptor.forClass(CertificateAlert.class);
        verify(alertDispatcher).dispatch(alert.capture());
        assertThat(alert.getValue().ownerType()).isEqualTo(OwnerType.SERVER);
        assertThat(alert.getValue().contact()).contains("published@example.gov", "on.call@example.gov");
    }

    @Test
    void reSyncingAnUnchangedEntryKeepsTheSameCachedCertificate() {
        directory.addUser("stable", "Stable Holder", "stable@example.gov",
                TestCertificates.expiringIn("stable", Duration.ofDays(400)));

        syncAll();
        Long firstId = certificateIdOf("uid=stable," + EmbeddedDirectory.PEOPLE_DN);

        DirectorySyncResult second = syncAll();

        assertThat(second.certificatesCached()).isZero();
        assertThat(certificateIdOf("uid=stable," + EmbeddedDirectory.PEOPLE_DN)).isEqualTo(firstId);
    }

    @Test
    void droppingACertificateFromTheDirectoryDropsItFromTheCache() {
        // Hold the bytes: each call to the generator mints a fresh certificate, and the
        // point here is that one of the two survives untouched.
        byte[] kept = TestCertificates.expiringIn("dropping-a", Duration.ofDays(100));
        byte[] dropped = TestCertificates.expiringIn("dropping-b", Duration.ofDays(200));
        String dn = directory.addUser("dropping", "Dropping Holder", "dropping@example.gov", kept, dropped);

        syncAll();
        DirectoryUser before = userByDn(dn);
        assertThat(before.getCertificateCount()).isEqualTo(2);
        Long keptId = fingerprintToId(before, TestCertificates.sha256(kept));

        directory.replaceCertificates(dn, kept);
        DirectorySyncResult result = syncService.syncUsers();

        assertThat(result.certificatesRemoved()).isEqualTo(1);
        assertThat(result.certificatesCached()).isZero();
        DirectoryUser user = userByDn(dn);
        assertThat(user.getCertificateCount()).isEqualTo(1);
        // The surviving certificate is the same cached row, not a re-created one.
        assertThat(user.getCertificates().getFirst().getId()).isEqualTo(keptId);
    }

    private Long fingerprintToId(DirectoryUser user, String fingerprint) {
        return user.getCertificates().stream()
                .filter(certificate -> certificate.getSha256Fingerprint().equals(fingerprint))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    @Test
    void anEntryWithNoCertificateIsStillTracked() {
        directory.addUser("bare", "Bare Holder", "bare@example.gov");

        syncAll();

        DirectoryUser user = userRepository.findByDn("uid=bare," + EmbeddedDirectory.PEOPLE_DN).orElseThrow();
        assertThat(user.getCertificateCount()).isZero();
        assertThat(user.getCertificateStatus()).isEqualTo(CertificateStatus.NONE);
        assertThat(user.getEarliestExpiry()).isNull();
        assertThat(user.getLastSyncedAt()).isNotNull();
    }

    /** Runs both sweeps, as the schedule does. */
    private DirectorySyncResult syncAll() {
        DirectorySyncResult users = syncService.syncUsers();
        DirectorySyncResult servers = syncService.syncServers();
        return new DirectorySyncResult(
                users.job(),
                users.entriesSeen() + servers.entriesSeen(),
                users.entriesCreated() + servers.entriesCreated(),
                users.certificatesCached() + servers.certificatesCached(),
                users.certificatesRemoved() + servers.certificatesRemoved(),
                users.alertsRaised() + servers.alertsRaised(),
                0,
                users.errors() + servers.errors(),
                users.duration().plus(servers.duration()));
    }

    private Long certificateIdOf(String dn) {
        List<CachedCertificate> certificates = userByDn(dn).getCertificates();
        return certificates.getFirst().getId();
    }

    /** Loads a user with its certificates initialised, for assertions outside a transaction. */
    private DirectoryServer serverWithPocs(String dn) {
        return inTransaction(() -> {
            DirectoryServer server = serverRepository.findByDn(dn).orElseThrow();
            server.getServerPocs().size();
            return server;
        });
    }

    private DirectoryServer serverByDn(String dn) {
        return inTransaction(() -> {
            DirectoryServer server = serverRepository.findByDn(dn).orElseThrow();
            server.getCertificates().size();
            return server;
        });
    }

    private DirectoryUser userByDn(String dn) {
        return inTransaction(() -> {
            DirectoryUser user = userRepository.findByDn(dn).orElseThrow();
            user.getCertificates().size();
            return user;
        });
    }

    /** Runs a read inside a transaction, for assertions that touch lazy state. */
    private <T> T inTransaction(java.util.function.Supplier<T> read) {
        return transactionTemplate.execute(status -> read.get());
    }

    private static void await(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a certificate to expire", e);
        }
    }
}

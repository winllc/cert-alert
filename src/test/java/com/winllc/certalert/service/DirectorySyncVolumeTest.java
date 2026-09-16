package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.alert.AlertDispatcher;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.SyncJob;
import com.winllc.certalert.domain.SyncStatus;
import com.winllc.certalert.repository.CachedCertificateRepository;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectorySpecifications;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.SyncRunRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Drives the scrape over far more entries than fit in one LDAP page or one write batch.
 *
 * <p>Two thousand entries is not a hundred thousand, but it crosses the boundaries that
 * matter: the paged results control has to page, the collector has to flush repeatedly,
 * and the persistence context has to be cleared between batches. A bug in any of those
 * shows up here rather than in production.
 */
@SpringBootTest
@ActiveProfiles("test")
class DirectorySyncVolumeTest {

    private static final int USERS = 2000;
    private static final int SERVERS = 500;

    private static EmbeddedDirectory directory;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private DirectoryUserRepository userRepository;

    @Autowired
    private DirectoryServerRepository serverRepository;

    @Autowired
    private CachedCertificateRepository certificateRepository;

    @Autowired
    private SyncRunRepository syncRunRepository;

    @MockitoBean
    private AlertDispatcher alertDispatcher;

    /**
     * The whole suite shares one in-memory database, and these tests assert on absolute
     * counts, so they start from an empty one.
     */
    @BeforeEach
    void reset() {
        serverRepository.deleteAll();
        userRepository.deleteAll();
    }

    @BeforeAll
    static void startDirectory() {
        directory = new EmbeddedDirectory();
        byte[] certificate = TestCertificates.expiringIn("bulk.example.gov", Duration.ofDays(365));
        directory.addUsers(USERS, certificate);
        directory.addServers(SERVERS, certificate);
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
        // Realistic sizes rather than the tiny test defaults, so the run is not dominated
        // by round trips, while still needing many pages and many batches.
        registry.add("cert-alert.ldap.page-size", () -> 100);
        registry.add("cert-alert.ldap.batch-size", () -> 250);
    }

    @Test
    void scrapesEveryEntryAcrossManyPagesAndBatches() {
        DirectorySyncResult users = syncService.syncUsers();
        DirectorySyncResult servers = syncService.syncServers();

        // Every entry crossed the paging boundary and landed.
        assertThat(users.entriesSeen()).isEqualTo(USERS);
        assertThat(users.entriesCreated()).isEqualTo(USERS);
        assertThat(users.errors()).isZero();
        assertThat(servers.entriesSeen()).isEqualTo(SERVERS);
        assertThat(servers.entriesCreated()).isEqualTo(SERVERS);
        assertThat(servers.errors()).isZero();

        assertThat(userRepository.count()).isEqualTo(USERS);
        assertThat(serverRepository.count()).isEqualTo(SERVERS);

        // The fixture gives every tenth entry the same certificate. It is cached once per
        // owner, never shared, so the count follows the owners rather than the bytes.
        assertThat(certificateRepository.count()).isEqualTo(USERS / 10 + SERVERS / 10);
    }

    @Test
    void reSyncingTheWholeDirectoryWritesNoNewCertificates() {
        syncService.syncUsers();
        long certificatesAfterFirst = certificateRepository.count();

        DirectorySyncResult second = syncService.syncUsers();

        assertThat(second.entriesSeen()).isEqualTo(USERS);
        // Everything was already known: no creates, no new certificates, nothing removed.
        assertThat(second.entriesCreated()).isZero();
        assertThat(second.certificatesCached()).isZero();
        assertThat(second.certificatesRemoved()).isZero();
        assertThat(certificateRepository.count()).isEqualTo(certificatesAfterFirst);
    }

    @Test
    void theContactJoinStillResolvesAcrossTheWholeDirectory() {
        syncService.syncUsers();
        syncService.syncServers();

        // bulk00000 is the contact for bulksrv00000; the fixture pairs every fifth one.
        var user = userRepository.findByDn("uid=bulk00000," + EmbeddedDirectory.PEOPLE_DN).orElseThrow();
        List<DirectoryServer> theirServers =
                serverRepository.findAll(DirectorySpecifications.pointOfContactAnyOf(
                        userRepository.findIdentifiersById(user.getId())));

        assertThat(theirServers).hasSize(1);
        assertThat(theirServers.getFirst().getCommonName()).isEqualTo("bulksrv00000");
    }

    @Test
    void everyRunIsRecorded() {
        syncService.syncUsers();

        var run = syncRunRepository
                .findFirstByJobAndStatusInOrderByStartedAtDesc(
                        SyncJob.USERS, List.of(SyncStatus.COMPLETED, SyncStatus.COMPLETED_WITH_ERRORS))
                .orElseThrow();

        assertThat(run.getEntriesSeen()).isEqualTo(USERS);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.getStatus()).isEqualTo(SyncStatus.COMPLETED);
    }

    @Test
    void cachedCertificatesAreClassifiedConsistentlyAtVolume() {
        syncService.syncUsers();

        assertThat(certificateRepository.findAll())
                .isNotEmpty()
                .allSatisfy(certificate ->
                        assertThat(certificate.getStatus()).isEqualTo(CertificateStatus.VALID));
    }
}

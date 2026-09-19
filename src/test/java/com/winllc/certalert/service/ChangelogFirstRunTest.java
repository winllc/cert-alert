package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

import com.winllc.certalert.domain.ChangelogCursor;
import com.winllc.certalert.repository.ChangelogCursorRepository;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestCertificates;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ldap.UncategorizedLdapException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Switching the connector on against an empty cache.
 *
 * <p>Following a changelog keeps a cache current; it never populates one. Everything the
 * directory held before the connector started has no change to announce it, so the first
 * poll with no stored position reads the tree - and these cases are what says it does, and
 * that it does it once.
 */
@SpringBootTest
@ActiveProfiles("test")
class ChangelogFirstRunTest {

    private static EmbeddedDirectory directory;

    @Autowired
    private ChangelogConnector connector;

    @Autowired
    private ChangelogCursorStore cursorStore;

    /** Real for every case but one, which needs a sweep that fails part-way. */
    @MockitoSpyBean
    private DirectorySyncService syncService;

    @Autowired
    private ChangelogCursorRepository cursorRepository;

    @Autowired
    private DirectoryUserRepository userRepository;

    @Autowired
    private DirectoryServerRepository serverRepository;

    @BeforeAll
    static void startDirectory() {
        directory = new EmbeddedDirectory(1000);
        // Already in the directory before anything starts following it: exactly what a
        // changelog has nothing to say about.
        directory.addUser("established", "Established Person", "established@example.gov",
                TestCertificates.expiringIn("established", Duration.ofDays(120)));
        directory.addUser("longstanding", "Longstanding Person", "longstanding@example.gov");
        directory.addServer("oldsrv", "https://oldsrv.example.gov",
                new String[] {"established@example.gov"},
                TestCertificates.expiringIn("oldsrv", Duration.ofDays(45)));
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
        registry.add("cert-alert.ldap.changelog.enabled", () -> "true");
        // Nothing polls behind these cases; the first poll each one makes is the first start.
        registry.add("cert-alert.ldap.changelog.auto-start", () -> "false");
        registry.add("cert-alert.ldap.changelog.filter", () -> "(objectClass=changeLogEntry)");
    }

    /** An empty cache and no stored position: a deployment that has just switched it on. */
    @BeforeEach
    void startFromNothing() {
        serverRepository.deleteAll();
        userRepository.deleteAll();
        cursorRepository.deleteAll();
    }

    @Test
    void theFirstPollImportsWhatTheDirectoryAlreadyHeld() {
        connector.pollOnce();

        assertThat(userRepository.findByDn("uid=established," + EmbeddedDirectory.PEOPLE_DN)).isPresent();
        assertThat(userRepository.findByDn("uid=longstanding," + EmbeddedDirectory.PEOPLE_DN)).isPresent();
        assertThat(serverRepository.findByDn("cn=oldsrv," + EmbeddedDirectory.SERVERS_DN)).isPresent();
        // Both sweeps, not just the people one.
        assertThat(userRepository.count()).isEqualTo(2);
        assertThat(serverRepository.count()).isEqualTo(1);
    }

    @Test
    void itTakesUpAPositionSoItFollowsFromThereOn() {
        connector.pollOnce();

        ChangelogCursor cursor = cursorStore.find().orElseThrow();
        // At the tip: everything up to here is in the cache because the sweep read it, not
        // because the changes were applied, so there is nothing behind to replay.
        assertThat(cursor.getLastChangeNumber()).isPositive();
        assertThat(cursor.lag()).isZero();

        // And following works from that position, without a second import.
        String dn = directory.addUser("afterwards", "Afterwards Person", "afterwards@example.gov");
        connector.pollOnce();

        assertThat(userRepository.findByDn(dn)).isPresent();
        assertThat(cursorStore.find().orElseThrow().getChangesApplied()).isEqualTo(1);
    }

    @Test
    void itImportsOnceRatherThanOnEveryPoll() {
        connector.pollOnce();
        String dn = "uid=longstanding," + EmbeddedDirectory.PEOPLE_DN;
        assertThat(userRepository.findByDn(dn)).isPresent();

        // Gone from the cache with nothing in the changelog to say so. A second import
        // would put it back; following cannot, and must not try.
        userRepository.deleteAll(userRepository.findByDn(dn).stream().toList());

        connector.pollOnce();

        assertThat(userRepository.findByDn(dn)).isEmpty();
    }

    @Test
    void anImportThatFailsLeavesNoPositionBehind() {
        // A directory that goes away part-way through the import. Had a cursor been written
        // first, the connector would come back following a cache that was never filled - and
        // nothing afterwards would ever fill it.
        doThrow(new UncategorizedLdapException("the directory went away"))
                .when(syncService)
                .syncServers();

        assertThatThrownBy(() -> connector.pollOnce()).isInstanceOf(UncategorizedLdapException.class);

        // No position, so the next poll starts the import over rather than following on.
        assertThat(cursorStore.find()).isEmpty();
    }
}

package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.ChangelogCursor;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.ChangelogCursorRepository;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.web.dto.ChangelogStatus;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drives the changelog connector against a real directory, one poll at a time.
 *
 * <p>The loop is left stopped ({@code auto-start: false}) and {@link ChangelogConnector#pollOnce()}
 * called directly, so each test says exactly what the connector saw rather than racing a
 * background thread.
 */
@SpringBootTest
@ActiveProfiles("test")
class ChangelogConnectorTest {

    private static EmbeddedDirectory directory;

    @Autowired
    private ChangelogConnector connector;

    @Autowired
    private ChangelogCursorStore cursorStore;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private DirectoryUserRepository userRepository;

    @Autowired
    private DirectoryServerRepository serverRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ChangelogCursorRepository cursorRepository;

    @BeforeAll
    static void startDirectory() {
        // The server keeps its own changelog, so these tests read a real one.
        directory = new EmbeddedDirectory(1000);
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
        // The tests drive the loop; nothing should poll behind them.
        registry.add("cert-alert.ldap.changelog.auto-start", () -> "false");
        // These cases prime the connector with a poll of their own; the first-run import is
        // ChangelogFirstRunTest's subject, and a sweep here would populate the cache the
        // changes are supposed to be what fills.
        registry.add("cert-alert.ldap.changelog.full-sync-on-first-run", () -> "false");
        // The in-memory server has no schema, so 'changeNumber>=N' would compare as text.
        // The connector re-checks every number it reads, which is what this leans on.
        registry.add("cert-alert.ldap.changelog.filter", () -> "(objectClass=changeLogEntry)");
    }

    /**
     * Each case starts with an empty cache and the connector sitting at the directory's
     * current tip - which is what it does on a first start, having just been given a cache
     * populated by a sweep. The priming poll is that start.
     */
    @BeforeEach
    void reset() {
        serverRepository.deleteAll();
        userRepository.deleteAll();
        cursorRepository.deleteAll();
        connector.pollOnce();
    }

    // --- the cases ---------------------------------------------------------------------

    /**
     * A person of exactly the right shape, in the wrong part of the tree.
     *
     * <p>The changelog covers whatever the directory was told to record, which is more of
     * the tree than either sweep reads. Matching the objectClass filter is therefore not
     * enough on its own: this entry would be picked up by no sweep, and caching it from a
     * change leaves a row the sweeps can neither refresh nor account for.
     */
    @Test
    void aPersonOutsideTheSweptSubtreeIsNotCached() {
        String contractors = directory.addOrganizationalUnit("contractors");
        String outside = directory.addUserUnder(contractors, "outsider", "Out Sider", "out.sider@example.gov",
                TestCertificates.expiringIn("outsider", Duration.ofDays(200)));

        // A person the sweep would collect, added in the same batch, so what is being shown
        // is where the entry lives and not that the poll did nothing.
        String inside = directory.addUser("insider", "In Sider", "in.sider@example.gov",
                TestCertificates.expiringIn("insider", Duration.ofDays(200)));

        connector.pollOnce();

        assertThat(userRepository.findByDn(inside)).isPresent();
        assertThat(userRepository.findByDn(outside))
                .as("a person under ou=contractors, which no sweep reads")
                .isEmpty();
    }

    /**
     * And one already cached is dropped when the changelog next names it, so a cache that
     * collected such entries before this was checked settles itself rather than needing to
     * be emptied by hand.
     */
    @Test
    void andOneAlreadyCachedIsDroppedWhenItChanges() {
        String contractors = directory.addOrganizationalUnit("contractors2");
        String outside = directory.addUserUnder(contractors, "stale", "Stale Entry", "stale.entry@example.gov",
                TestCertificates.expiringIn("stale", Duration.ofDays(200)));
        connector.pollOnce();

        // Put it in the cache the way the connector used to, bypassing the reader.
        transactionTemplate.execute(status -> {
            DirectoryUser stranded = new DirectoryUser(outside);
            stranded.setUid("stale");
            stranded.setDisplayName("Stale Entry");
            stranded.markSynced(java.time.Instant.now());
            return userRepository.save(stranded);
        });
        assertThat(userRepository.findByDn(outside)).isPresent();

        directory.modify(outside, "displayName", "Stale Entry Renamed");
        connector.pollOnce();

        assertThat(userRepository.findByDn(outside)).isEmpty();
    }

    @Test
    void anAddedPersonAppearsWithoutASweep() {
        String dn = directory.addUser("newjoiner", "New Joiner", "new.joiner@example.gov",
                TestCertificates.expiringIn("newjoiner", Duration.ofDays(200)));

        connector.pollOnce();

        DirectoryUser user = userRepository.findByDn(dn).orElseThrow();
        assertThat(user.getDisplayName()).isEqualTo("New Joiner");
        assertThat(user.getCertificateCount()).isEqualTo(1);
    }

    @Test
    void aModifiedAttributeIsPickedUp() {
        String dn = directory.addUser("changer", "Original Name", "changer@example.gov");
        syncService.syncUsers();
        assertThat(userRepository.findByDn(dn).orElseThrow().getDisplayName()).isEqualTo("Original Name");

        directory.modify(dn, "displayName", "Corrected Name");

        connector.pollOnce();

        assertThat(userRepository.findByDn(dn).orElseThrow().getDisplayName()).isEqualTo("Corrected Name");
    }

    @Test
    void aNewlyPublishedCertificateIsCached() {
        String dn = directory.addUser("certless", "Cert Less", "certless@example.gov");
        syncService.syncUsers();
        assertThat(userRepository.findByDn(dn).orElseThrow().getCertificateCount()).isZero();

        directory.replaceCertificates(dn, TestCertificates.expiringIn("certless", Duration.ofDays(10)));

        connector.pollOnce();

        DirectoryUser user = inTransaction(() -> {
            DirectoryUser loaded = userRepository.findByDn(dn).orElseThrow();
            loaded.getCertificates().size();
            return loaded;
        });
        assertThat(user.getCertificateCount()).isEqualTo(1);
        // The whole cached-certificate pipeline ran, not just the attribute copy.
        CachedCertificate certificate = user.getCertificates().getFirst();
        assertThat(certificate.getSha256Fingerprint()).hasSize(64);
        assertThat(user.getCertificateStatus()).isEqualTo(
                com.winllc.certalert.domain.CertificateStatus.EXPIRING_SOON);
    }

    @Test
    void aDeletedEntryIsRemovedFromTheCache() {
        String dn = directory.addUser("leaver", "Leaver Person", "leaver@example.gov");
        syncService.syncUsers();
        assertThat(userRepository.findByDn(dn)).isPresent();

        directory.delete(dn);

        connector.pollOnce();

        assertThat(userRepository.findByDn(dn)).isEmpty();
    }

    @Test
    void aRenamedEntryMovesRatherThanDuplicating() {
        String dn = directory.addUser("oldname", "Renamed Person", "renamed@example.gov");
        syncService.syncUsers();

        directory.rename(dn, "uid=newname");
        String newDn = "uid=newname," + EmbeddedDirectory.PEOPLE_DN;

        connector.pollOnce();

        // Gone from the old name, present under the new one: moved, not duplicated. An
        // absolute count would say nothing here - the directory is shared across these
        // cases, so a sweep picks up whatever the other ones left behind.
        assertThat(userRepository.findByDn(dn)).isEmpty();
        assertThat(userRepository.findByDn(newDn)).isPresent();
    }

    @Test
    void aServerChangeIsAppliedToTheRightObjectType() {
        String dn = directory.addServer("newsrv", "https://newsrv.example.gov",
                new String[] {"alice@example.gov"},
                TestCertificates.expiringIn("newsrv", Duration.ofDays(300)));

        connector.pollOnce();

        assertThat(serverRepository.findByDn(dn)).isPresent();
        assertThat(userRepository.findByDn(dn)).isEmpty();
    }

    @Test
    void aChangeToSomethingUntrackedIsCountedAndSteppedOver() {
        long before = cursor().getLastChangeNumber();
        // An organizational unit matches neither search filter.
        directory.modify(EmbeddedDirectory.PEOPLE_DN, "description", "reorganised");

        connector.pollOnce();

        ChangelogCursor cursor = cursor();
        assertThat(cursor.getChangesIgnored()).isEqualTo(1);
        assertThat(cursor.getChangesApplied()).isZero();
        // It still moved past it, so the next poll does not see it again.
        assertThat(cursor.getLastChangeNumber()).isGreaterThan(before);
    }

    @Test
    void theCursorMovesForwardAndTheSameChangeIsNotAppliedTwice() {
        long before = cursor().getLastChangeNumber();
        directory.addUser("once", "Once Only", "once@example.gov");

        connector.pollOnce();
        ChangelogCursor afterFirst = cursor();
        assertThat(afterFirst.getLastChangeNumber()).isGreaterThan(before);
        assertThat(afterFirst.getChangesApplied()).isEqualTo(1);

        // Nothing new: the second poll reads nothing and changes no counters.
        connector.pollOnce();
        ChangelogCursor afterSecond = cursor();
        assertThat(afterSecond.getLastChangeNumber()).isEqualTo(afterFirst.getLastChangeNumber());
        assertThat(afterSecond.getChangesApplied()).isEqualTo(1);
    }

    @Test
    void aBatchOfChangesIsAppliedInOnePoll() {
        long before = cursor().getLastChangeNumber();
        directory.addUser("batch1", "Batch One", "batch1@example.gov");
        directory.addUser("batch2", "Batch Two", "batch2@example.gov");
        directory.addUser("batch3", "Batch Three", "batch3@example.gov");

        int read = connector.pollOnce();

        assertThat(read).isEqualTo(3);
        assertThat(userRepository.count()).isEqualTo(3);
        assertThat(cursor().getLastChangeNumber()).isEqualTo(before + 3);
    }

    @Test
    void anEntryThatStopsMatchingTheSearchFilterIsRemoved() {
        String dn = directory.addUser("departing", "Departing Person", "departing@example.gov");
        syncService.syncUsers();
        assertThat(userRepository.findByDn(dn)).isPresent();

        // Still in the directory, but no longer an icOrgPerson - so no longer ours to hold.
        directory.modify(dn, "objectClass", "top", "person", "inetOrgPerson");

        connector.pollOnce();

        assertThat(userRepository.findByDn(dn)).isEmpty();
    }

    @Test
    void withTheFirstRunImportOffTheCacheIsLeftToTheSweeps() {
        // The directory holds people from the other cases; @BeforeEach emptied the cache and
        // primed the cursor, and nothing has changed since. With the import off, following
        // alone finds none of them.
        assertThat(userRepository.count()).isZero();

        connector.pollOnce();

        assertThat(userRepository.count()).isZero();
    }

    @Test
    void theStatusReportsWhereTheConnectorHasGotTo() {
        directory.addUser("reported", "Reported Person", "reported@example.gov");
        connector.pollOnce();

        ChangelogStatus status = ChangelogStatus.from(cursor(), connector.isRunning());

        assertThat(status.enabled()).isTrue();
        assertThat(status.lastChangeNumber()).isEqualTo(cursor().getLastChangeNumber());
        assertThat(status.changesApplied()).isEqualTo(1);
        // Caught up: the connector has read everything the directory has recorded.
        assertThat(status.lag()).isZero();
    }

    private ChangelogCursor cursor() {
        return cursorStore.find().orElseThrow();
    }

    private <T> T inTransaction(java.util.function.Supplier<T> read) {
        return transactionTemplate.execute(status -> read.get());
    }
}

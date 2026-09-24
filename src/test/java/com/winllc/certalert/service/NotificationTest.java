package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.domain.Notification;
import com.winllc.certalert.domain.NotificationKind;
import com.winllc.certalert.domain.Severity;
import com.winllc.certalert.domain.UserEmailAlias;
import com.winllc.certalert.repository.NotificationRepository;
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
 * Who gets told what.
 *
 * <p>Driven through the real sweep, because the interesting part is the resolution: a
 * certificate belongs to an entry, an entry has points of contact, and a point of contact
 * may be a person, an address nobody has claimed, or a list several people answer.
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationTest {

    private static EmbeddedDirectory directory;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private UserEmailAliasService aliasService;

    @Autowired
    private ServerContactService contactService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private com.winllc.certalert.repository.ProjectRepository projectRepository;

    @Autowired
    private NotificationRepository notifications;

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
    }

    @BeforeEach
    void reset() {
        notifications.deleteAll();
        projectRepository.deleteAll();
        serverRepository.deleteAll();
        userRepository.deleteAll();
    }

    /**
     * A certificate crossing into expiry is told to the person whose certificate it is,
     * where it waits for them to sign in.
     */
    @Test
    void aPersonIsToldAboutTheirOwnCertificate() throws InterruptedException {
        String dn = directory.addUser("owner", "Own Er", "own.er@example.gov",
                TestCertificates.expiringIn("owner", Duration.ofSeconds(2)));
        syncService.syncUsers();
        Thread.sleep(Duration.ofSeconds(3).toMillis());

        syncService.syncUsers();

        Long userId = userRepository.findByDn(dn).orElseThrow().getId();
        List<Notification> mine = notificationService
                .forRecipient(userId, PageRequest.of(0, 10))
                .getContent();
        assertThat(mine).hasSize(1);
        assertThat(mine.getFirst().getKind()).isEqualTo(NotificationKind.CERTIFICATE_STATUS);
        assertThat(mine.getFirst().getSeverity()).isEqualTo(Severity.CRITICAL);
        assertThat(mine.getFirst().getRecipientAddress()).isEqualTo("own.er@example.gov");
        assertThat(mine.getFirst().isUnread()).isTrue();
        assertThat(notificationService.unreadCount(userId)).isEqualTo(1);
    }

    /**
     * A server's certificate is its points of contact's business - all of them, from both
     * places a contact comes from.
     */
    @Test
    void everyPointOfContactOfAServerIsTold() throws InterruptedException {
        String publishedDn = directory.addUser("published", "Pub Lished", "published@example.gov");
        String addedDn = directory.addUser("added", "Add Ed", "added@example.gov");
        String serverDn = directory.addServer(
                "watched",
                "https://watched.example.gov",
                new String[] {"published@example.gov"},
                TestCertificates.expiringIn("watched.example.gov", Duration.ofSeconds(2)));
        syncService.syncUsers();
        syncService.syncServers();

        Long serverId = serverRepository.findByDn(serverDn).orElseThrow().getId();
        Long addedId = userRepository.findByDn(addedDn).orElseThrow().getId();
        contactService.addUser(serverId, addedId, "alice");

        Thread.sleep(Duration.ofSeconds(3).toMillis());
        syncService.syncServers();

        Long publishedId = userRepository.findByDn(publishedDn).orElseThrow().getId();
        assertThat(notificationService.unreadCount(publishedId)).as("named by the directory").isEqualTo(1);
        assertThat(notificationService.unreadCount(addedId)).as("added here").isEqualTo(1);
    }

    /** A list resolves to everybody on it, which is the point of claiming one. */
    @Test
    void everyoneOnAListIsToldAboutTheServerNamedAfterIt() throws InterruptedException {
        String firstDn = directory.addUser("one", "One Person", "one@example.gov");
        String secondDn = directory.addUser("two", "Two Person", "two@example.gov");
        String serverDn = directory.addServer(
                "listed",
                "https://listed.example.gov",
                new String[] {"platform@example.gov"},
                TestCertificates.expiringIn("listed.example.gov", Duration.ofSeconds(2)));
        syncService.syncUsers();
        syncService.syncServers();

        Long firstId = userRepository.findByDn(firstDn).orElseThrow().getId();
        Long secondId = userRepository.findByDn(secondDn).orElseThrow().getId();
        aliasService.add(firstId, "platform@example.gov", UserEmailAlias.Kind.GROUP, "Platform list", "alice");
        aliasService.add(secondId, "platform@example.gov", UserEmailAlias.Kind.GROUP, "Platform list", "alice");

        Thread.sleep(Duration.ofSeconds(3).toMillis());
        syncService.syncServers();

        assertThat(notificationService.unreadCount(firstId)).isEqualTo(1);
        assertThat(notificationService.unreadCount(secondId)).isEqualTo(1);
        assertThat(notifications.findAll())
                .extracting(Notification::getSubjectDn)
                .containsOnly(serverDn);
    }

    /** A sweep every night must not mean the same message every night. */
    @Test
    void nobodyIsToldTheSameThingTwice() throws InterruptedException {
        String dn = directory.addUser("repeating", "Rep Eating", "rep@example.gov",
                TestCertificates.expiringIn("repeating", Duration.ofSeconds(2)));
        syncService.syncUsers();
        Thread.sleep(Duration.ofSeconds(3).toMillis());

        syncService.syncUsers();
        long afterFirst = notifications.count();
        // A second sweep re-evaluates the same certificate in the same state.
        syncService.syncUsers();

        assertThat(afterFirst).isEqualTo(1);
        assertThat(notifications.count()).isEqualTo(afterFirst);
        Long userId = userRepository.findByDn(dn).orElseThrow().getId();
        assertThat(notificationService.unreadCount(userId)).isEqualTo(1);
    }

    /**
     * The round-up is a different thing from the transition alerts: it reports what is
     * expiring now, whether or not anything changed, which is what somebody arriving at a
     * directory that has been rotting for a year needs.
     */
    @Test
    void theDigestReportsWhatIsExpiringWhetherOrNotItJustChanged() {
        // The directory is shared across this class, so counts across the whole run are not
        // this test's to assert; what this person was told is.

        String dn = directory.addUser("digested", "Di Gested", "di@example.gov",
                TestCertificates.expiringIn("digested", Duration.ofDays(5)));
        syncService.syncUsers();
        // Nothing transitioned: the certificate was expiring the first time it was seen.
        assertThat(notifications.count()).isZero();

        notificationService.digest();

        Long userId = userRepository.findByDn(dn).orElseThrow().getId();
        List<Notification> mine = notificationService
                .forRecipient(userId, PageRequest.of(0, 10))
                .getContent();
        assertThat(mine).hasSize(1);
        assertThat(mine.getFirst().getKind()).isEqualTo(NotificationKind.EXPIRY_DIGEST);
        assertThat(mine.getFirst().getMessage()).contains("1 credential(s) expiring").contains("Di Gested");
        // Email is off in the tests, so nothing claims to have been sent.
        assertThat(mine.getFirst().getEmailedAt()).isNull();
    }

    /** One person with several expiring certificates gets one round-up, not several. */
    @Test
    void theDigestIsOneMessagePerPerson() {
        String userDn = directory.addUser("busy", "Bus Y", "busy@example.gov",
                TestCertificates.expiringIn("busy-a", Duration.ofDays(3)),
                TestCertificates.expiringIn("busy-b", Duration.ofDays(6)));
        directory.addServer(
                "theirs",
                "https://theirs.example.gov",
                new String[] {"busy@example.gov"},
                TestCertificates.expiringIn("theirs.example.gov", Duration.ofDays(9)));
        syncService.syncUsers();
        syncService.syncServers();

        notificationService.digest();

        Long userId = userRepository.findByDn(userDn).orElseThrow().getId();
        List<Notification> mine = notificationService
                .forRecipient(userId, PageRequest.of(0, 10))
                .getContent();
        // One message, listing all three, rather than one message per certificate.
        assertThat(mine).hasSize(1);
        assertThat(mine.getFirst().getMessage())
                .contains("3 credential(s) expiring")
                .contains("across 2 directory entries");
    }

    /**
     * Whoever runs a project hears about the servers in it. They are not points of contact -
     * the directory has never heard of them - but they are answerable for the thing the
     * server is part of, which is what the role is for.
     */
    @Test
    void whoeverRunsAProjectIsToldAboutItsServers() throws InterruptedException {
        String runnerDn = directory.addUser("runner", "Run Ner", "runner@example.gov");
        String serverDn = directory.addServer(
                "project-server",
                "https://project-server.example.gov",
                new String[] {"someone.else@example.gov"},
                TestCertificates.expiringIn("project-server.example.gov", Duration.ofSeconds(2)));
        syncService.syncUsers();
        syncService.syncServers();

        Long runnerId = userRepository.findByDn(runnerDn).orElseThrow().getId();
        Long serverId = serverRepository.findByDn(serverDn).orElseThrow().getId();
        var project = projectService.create("Payroll migration", null, "alice");
        projectService.addAdmin(project.getId(), runnerId);
        projectService.addServer(project.getId(), serverId);

        Thread.sleep(Duration.ofSeconds(3).toMillis());
        syncService.syncServers();

        assertThat(notificationService.unreadCount(runnerId)).isEqualTo(1);
    }

    /** Being in the project is not the same thing, and does not come with the post. */
    @Test
    void merelyBeingInTheProjectIsNotTold() {
        String memberDn = directory.addUser("bystander", "By Stander", "bystander@example.gov");
        String serverDn = directory.addServer(
                "quiet-server",
                "https://quiet-server.example.gov",
                new String[] {"someone.else@example.gov"},
                TestCertificates.expiringIn("quiet-server.example.gov", Duration.ofDays(5)));
        syncService.syncUsers();
        syncService.syncServers();

        Long memberId = userRepository.findByDn(memberDn).orElseThrow().getId();
        Long serverId = serverRepository.findByDn(serverDn).orElseThrow().getId();
        var project = projectService.create("Quiet project", null, "alice");
        projectService.addMember(project.getId(), memberId);
        projectService.addServer(project.getId(), serverId);

        notificationService.digest();

        assertThat(notificationService.unreadCount(memberId)).isZero();
    }

    /** An address nobody has claimed can still be written to; it just has nobody to show. */
    @Test
    void anAddressWithNobodyBehindItIsStillARecipient() {
        String serverDn = directory.addServer(
                "orphaned",
                "https://orphaned.example.gov",
                new String[] {"nobody-claims-this@example.gov"},
                TestCertificates.expiringIn("orphaned.example.gov", Duration.ofDays(4)));
        syncService.syncServers();

        notificationService.digest();

        List<Notification> unclaimed = notifications.findAll().stream()
                .filter(notification -> "nobody-claims-this@example.gov".equals(notification.getRecipientAddress()))
                .toList();
        assertThat(unclaimed).hasSize(1);
        assertThat(unclaimed.getFirst().getRecipientUserId()).as("nobody to show it to").isNull();
        assertThat(serverRepository.findByDn(serverDn)).isPresent();
    }

    @Test
    void readingAndMarkingEverythingRead() {
        String dn = directory.addUser("reader", "Read Er", "reader@example.gov",
                TestCertificates.expiringIn("reader-a", Duration.ofDays(2)),
                TestCertificates.expiringIn("reader-b", Duration.ofDays(3)));
        syncService.syncUsers();
        notificationService.digest();
        Long userId = userRepository.findByDn(dn).orElseThrow().getId();
        Long first = notificationService
                .forRecipient(userId, PageRequest.of(0, 10))
                .getContent()
                .getFirst()
                .getId();

        assertThat(notificationService.unreadCount(userId)).isEqualTo(1);
        notificationService.markRead(userId, first);
        assertThat(notificationService.unreadCount(userId)).isZero();

        notificationService.digest();
        assertThat(notificationService.unreadCount(userId)).isEqualTo(1);
        assertThat(notificationService.markAllRead(userId)).isEqualTo(1);
        assertThat(notificationService.unreadCount(userId)).isZero();
    }
}

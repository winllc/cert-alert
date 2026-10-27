package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.Notification;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.repository.AuditEventRepository;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.NotificationRepository;
import com.winllc.certalert.repository.OutboundEmailRepository;
import com.winllc.certalert.repository.ProjectRepository;
import com.winllc.certalert.repository.ServerContactRepository;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.JobRunsAwait;
import com.winllc.certalert.support.TestCertificates;
import jakarta.mail.Address;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Who is told about which certificate, and what they actually read - end to end.
 *
 * <p>Driven from the real entry points (the sweeps and the hourly refresh the scheduler
 * runs - the scheduler itself is off in the tests - and the button that runs the round-up
 * by hand), against a real directory, database and
 * templates, with only the mail transport stubbed. Each person then signs in with a
 * password and reads their own notifications through the API the bell's page uses, so
 * what is asserted is what they see: whose certificate a line names, which page it links
 * to, what an email's subject and body say, and that nobody else's turns up.
 *
 * <p>The other notification tests check that the right number of notifications reach the
 * right people. These check that each one says the right thing about the right entry - a
 * notification addressed correctly but naming its reader instead of the server would pass
 * those and fail these.
 */
@SpringBootTest(
        properties = {
            "cert-alert.notifications.email.enabled=true",
            "cert-alert.notifications.email.from=cert-alert@example.gov",
            "cert-alert.alerts.email.enabled=true",
            "cert-alert.alerts.email.to=pki-team@example.gov"
        })
@ActiveProfiles("test")
class ExpiryNotificationJourneyTest {

    private static final String OPERATORS = "pki-team@example.gov";

    @TestConfiguration
    static class MailConfiguration {

        @Bean
        CapturingMailSender mailSender() {
            return new CapturingMailSender();
        }
    }

    /** Everything a real sender does up to opening a socket. */
    static class CapturingMailSender extends JavaMailSenderImpl {

        private final List<MimeMessage> sent = new CopyOnWriteArrayList<>();

        @Override
        protected void doSend(MimeMessage[] messages, Object[] originalMessages) {
            for (MimeMessage message : messages) {
                try {
                    message.saveChanges();
                } catch (jakarta.mail.MessagingException e) {
                    throw new IllegalStateException(e);
                }
                sent.add(message);
            }
        }
    }

    private static EmbeddedDirectory directory;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private CertificateRefreshService refreshService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ServerContactService contactService;

    @Autowired
    private MailOutbox outbox;

    @Autowired
    private CapturingMailSender mailSender;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private OutboundEmailRepository outboundEmails;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private ServerContactRepository contacts;

    @Autowired
    private DirectoryServerRepository servers;

    @Autowired
    private DirectoryUserRepository users;

    @Autowired
    private AuditEventRepository auditEvents;

    private MockMvc mockMvc;

    /** What each test put in the directory, taken out again so the next one sweeps only its own. */
    private final List<String> added = new ArrayList<>();

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
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        clear();
    }

    @AfterEach
    void removeEntries() {
        added.forEach(directory::delete);
        added.clear();
        clear();
    }

    private void clear() {
        mailSender.sent.clear();
        outboundEmails.deleteAll();
        notifications.deleteAll();
        projects.deleteAll();
        contacts.deleteAll();
        servers.deleteAll();
        users.deleteAll();
    }

    /**
     * A sweep sees a person's certificate and a server's certificate expire. The person is
     * told about their own; the server's published point of contact and whoever runs its
     * project are told about the server - by name, linking to the server's page - and
     * somebody with no connection to either is told nothing. The operators' email names the
     * entry and who to chase.
     */
    @Test
    void aSweepThatSeesCertificatesExpireTellsEachPersonAboutTheRightEntry() throws Exception {
        person("olive", "Olive Owner", "olive@example.gov",
                TestCertificates.expiringIn("olive", Duration.ofSeconds(2)));
        person("pat", "Pat Contact", "pat@example.gov");
        person("quinn", "Quinn Lead", "quinn@example.gov");
        person("uma", "Uma Elsewhere", "uma@example.gov");
        server("web01", new String[] {"pat@example.gov"},
                TestCertificates.expiringIn("web01.example.gov", Duration.ofSeconds(2)));

        syncService.syncUsers();
        syncService.syncServers();
        Long olive = userId("olive");
        Long quinn = userId("quinn");
        Long web01 = serverId("web01");
        var project = projectService.create("Payments", "The payments platform", "admin");
        projectService.addServer(project.getId(), web01);
        projectService.addAdmin(project.getId(), quinn);
        assertThat(notifications.count()).as("first sight of a certificate is not news").isZero();

        Thread.sleep(Duration.ofSeconds(3).toMillis());
        syncService.syncUsers();
        syncService.syncServers();

        // The server's point of contact reads about the server, not about themselves.
        for (String reader : List.of("pat", "quinn")) {
            String page = notificationsOf(signIn(reader));
            assertThat(JsonPath.<Integer>read(page, "$.content.length()")).as(reader).isEqualTo(1);
            assertThat(JsonPath.<String>read(page, "$.content[0].kind")).isEqualTo("CERTIFICATE_STATUS");
            assertThat(JsonPath.<String>read(page, "$.content[0].kindLabel")).isEqualTo("Certificate status");
            assertThat(JsonPath.<String>read(page, "$.content[0].severity")).isEqualTo("CRITICAL");
            assertThat(JsonPath.<String>read(page, "$.content[0].message"))
                    .as(reader + " reads which entry it is about")
                    .startsWith("[CRITICAL] Certificate for server web01 expired on ")
                    .doesNotContain("Pat Contact", "Quinn Lead", "Olive Owner");
            assertThat(JsonPath.<String>read(page, "$.content[0].subjectType")).isEqualTo("SERVER");
            assertThat(JsonPath.<String>read(page, "$.content[0].subjectName")).isEqualTo("web01");
            assertThat(JsonPath.<String>read(page, "$.content[0].link")).isEqualTo("/servers/" + web01);
            assertThat(JsonPath.<Boolean>read(page, "$.content[0].unread")).isTrue();
        }

        // The holder reads about their own certificate, and nothing about the server.
        MockHttpSession oliveSession = signIn("olive");
        String hers = notificationsOf(oliveSession);
        assertThat(JsonPath.<Integer>read(hers, "$.content.length()")).isEqualTo(1);
        assertThat(JsonPath.<String>read(hers, "$.content[0].message"))
                .startsWith("[CRITICAL] Certificate for user Olive Owner expired on ")
                .doesNotContain("web01");
        assertThat(JsonPath.<String>read(hers, "$.content[0].subjectName")).isEqualTo("Olive Owner");
        assertThat(JsonPath.<String>read(hers, "$.content[0].link")).isEqualTo("/users/" + olive);

        // Somebody with no connection to either hears nothing.
        MockHttpSession umaSession = signIn("uma");
        assertThat(JsonPath.<Integer>read(notificationsOf(umaSession), "$.content.length()")).isZero();
        assertThat(unreadCount(umaSession)).isZero();

        // One person's notification is not another's to mark read.
        MockHttpSession patSession = signIn("pat");
        Integer oliveNotification = JsonPath.read(hers, "$.content[0].id");
        mockMvc.perform(post("/api/v1/notifications/" + oliveNotification + "/read").session(patSession).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(unreadCount(oliveSession)).isEqualTo(1);
        mockMvc.perform(post("/api/v1/notifications/" + oliveNotification + "/read").session(oliveSession).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(unreadCount(oliveSession)).isZero();
        assertThat(unreadCount(patSession)).as("marking hers read leaves his alone").isEqualTo(1);

        // The operators hear about both, each naming its entry and who to chase.
        outbox.deliverDue();
        List<MimeMessage> operators = to(OPERATORS);
        assertThat(operators).extracting(this::subject)
                .containsExactlyInAnyOrder("[cert-alert] web01 - EXPIRED", "[cert-alert] Olive Owner - EXPIRED");
        String aboutServer = text(operators.stream()
                .filter(message -> subject(message).contains("web01")).findFirst().orElseThrow());
        assertThat(aboutServer)
                .contains("Certificate for server web01 expired on")
                .contains("Owner:     SERVER web01")
                .contains("DN:        cn=web01," + EmbeddedDirectory.SERVERS_DN)
                .contains("Contact:   pat@example.gov")
                .contains("Status:    EXPIRED");
        assertThat(to("pat@example.gov")).as("the transition alert is the operators' email, not the contact's")
                .isEmpty();

        // Recorded against the server, through both channels, to its contact.
        assertThat(alertsSentAbout(OwnerType.SERVER, web01))
                .extracting(AuditEvent::getChannel)
                .contains("email", "notifications");
        assertThat(alertsSentAbout(OwnerType.SERVER, web01))
                .allSatisfy(event -> assertThat(event.getTarget()).isEqualTo("pat@example.gov"));
    }

    /**
     * The round-up, run by an administrator from the page: one email to each person about
     * their own certificates, and a separate one to each of a server's contacts and project
     * administrators about the server. Each greets its reader and names the entry; nobody
     * receives anything about an entry that is not theirs, and the page says the same thing
     * the email did.
     */
    @Test
    void theRoundUpWritesToEachPersonAboutWhatIsTheirsAndNothingElse() throws Exception {
        person("admin", "Ada Admin", "ada@example.gov");
        person("olive", "Olive Owner", "olive@example.gov",
                TestCertificates.expiringIn("olive", Duration.ofDays(10).plusHours(12)));
        person("pat", "Pat Contact", "pat@example.gov");
        person("quinn", "Quinn Lead", "quinn@example.gov");
        person("uma", "Uma Elsewhere", "uma@example.gov");
        server("web01", new String[] {"pat@example.gov"},
                TestCertificates.expired("web01.example.gov", Duration.ofDays(2).plusHours(12)));
        syncService.syncUsers();
        syncService.syncServers();
        var project = projectService.create("Payments", null, "admin");
        projectService.addServer(project.getId(), serverId("web01"));
        projectService.addAdmin(project.getId(), userId("quinn"));

        runTheRoundUp();

        List<MimeMessage> olives = to("olive@example.gov");
        assertThat(olives).hasSize(1);
        assertThat(subject(olives.getFirst())).isEqualTo("[cert-alert] 1 of your credential(s) expiring soon");
        assertThat(text(olives.getFirst()))
                .startsWith("Hello Olive Owner,")
                .contains("Issued to Olive Owner (uid=olive," + EmbeddedDirectory.PEOPLE_DN + ")")
                .contains("Expires in 10 day(s), on ")
                .doesNotContain("web01");
        assertThat(html(olives.getFirst())).contains("Hello Olive Owner,").contains("Your certificates");

        for (String[] contact : List.of(
                new String[] {"pat@example.gov", "Pat Contact"}, new String[] {"quinn@example.gov", "Quinn Lead"})) {
            List<MimeMessage> theirs = to(contact[0]);
            assertThat(theirs).as(contact[0]).hasSize(1);
            assertThat(subject(theirs.getFirst())).isEqualTo("[cert-alert] 1 server certificate(s) expired");
            assertThat(text(theirs.getFirst()))
                    .startsWith("Hello " + contact[1] + ",")
                    .contains("You are a point of contact for a server whose certificate needs attention.")
                    .contains("Server web01 (cn=web01," + EmbeddedDirectory.SERVERS_DN + ")")
                    .contains("Expired 2 day(s) ago, on ")
                    .doesNotContain("Olive");
            assertThat(html(theirs.getFirst())).contains("Servers you are a contact for").contains("web01");
        }
        assertThat(to("uma@example.gov")).isEmpty();
        assertThat(to("ada@example.gov")).isEmpty();
        assertThat(to(OPERATORS)).as("the round-up is the contacts' email, not the operators'").isEmpty();

        // On the page, the round-up says what the email said, and records that it went.
        String page = notificationsOf(signIn("pat"));
        assertThat(JsonPath.<Integer>read(page, "$.content.length()")).isEqualTo(1);
        assertThat(JsonPath.<String>read(page, "$.content[0].kindLabel")).isEqualTo("Expiry summary");
        assertThat(JsonPath.<String>read(page, "$.content[0].severity")).isEqualTo("CRITICAL");
        assertThat(JsonPath.<String>read(page, "$.content[0].message"))
                .startsWith("1 credential(s) expired (web01 - CN=web01.example.gov - expired 2 day(s) ago, on ")
                .doesNotContain("Olive");
        assertThat(JsonPath.<String>read(page, "$.content[0].emailedAt")).isNotNull();
        // What it is about is the server it reports on, linked like any notification about
        // an entry - not the person reading it.
        assertThat(JsonPath.<String>read(page, "$.content[0].subjectType")).isEqualTo("SERVER");
        assertThat(JsonPath.<String>read(page, "$.content[0].subjectName")).isEqualTo("web01");
        assertThat(JsonPath.<String>read(page, "$.content[0].link")).isEqualTo("/servers/" + serverId("web01"));
        assertThat(page).doesNotContain("Pat Contact");

        String hers = notificationsOf(signIn("olive"));
        assertThat(JsonPath.<String>read(hers, "$.content[0].message"))
                .startsWith("1 credential(s) expiring (Olive Owner - CN=olive - expires in 10 day(s), on ")
                .doesNotContain("web01");
        // Her own certificate: the entry it is about is hers, and it says so.
        assertThat(JsonPath.<String>read(hers, "$.content[0].subjectName")).isEqualTo("Olive Owner");
        assertThat(JsonPath.<String>read(hers, "$.content[0].link")).isEqualTo("/users/" + userId("olive"));
    }

    /**
     * A round-up covering several entries is about all of them: the page says how many, and
     * names nobody - least of all the person reading it.
     */
    @Test
    void aRoundUpCoveringSeveralEntriesSaysHowManyAndNamesNobody() throws Exception {
        person("admin", "Ada Admin", "ada@example.gov");
        person("pat", "Pat Contact", "pat@example.gov");
        server("web01", new String[] {"pat@example.gov"},
                TestCertificates.expired("web01.example.gov", Duration.ofDays(2).plusHours(12)));
        server("web02", new String[] {"pat@example.gov"},
                TestCertificates.expiringIn("web02.example.gov", Duration.ofDays(5).plusHours(12)));
        syncService.syncUsers();
        syncService.syncServers();

        runTheRoundUp();

        String page = notificationsOf(signIn("pat"));
        assertThat(JsonPath.<Integer>read(page, "$.content.length()")).isEqualTo(1);
        assertThat(JsonPath.<String>read(page, "$.content[0].message"))
                .isEqualTo("1 credential(s) expired and 1 expiring across 2 directory entries");
        assertThat(JsonPath.<String>read(page, "$.content[0].subjectName")).isEqualTo("2 directory entries");
        assertThat(absentOrNull(page, "$.content[0].link")).isNull();
        assertThat(page).doesNotContain("Pat Contact");
    }

    /**
     * A round-up saved before round-ups named their entry has the reader as its subject. The
     * page must not show that, and must not break on it; the next run names the entry, and -
     * having nothing new to say - leaves it read.
     */
    @Test
    void aRoundUpSavedBeforeTheUpgradeNamesNobodyUntilTheNextRunNamesTheEntry() throws Exception {
        person("admin", "Ada Admin", "ada@example.gov");
        person("pat", "Pat Contact", "pat@example.gov");
        server("web01", new String[] {"pat@example.gov"},
                TestCertificates.expired("web01.example.gov", Duration.ofDays(2).plusHours(12)));
        syncService.syncUsers();
        syncService.syncServers();
        runTheRoundUp();

        // As the previous release stored it: about the reader, by a placeholder DN - and read.
        Long pat = userId("pat");
        Notification stored = notifications.findAll().stream()
                .filter(notification -> pat.equals(notification.getRecipientUserId()))
                .findFirst()
                .orElseThrow();
        stored.about(new AuditEvent.SubjectRef(
                OwnerType.USER, pat, NotificationService.LEGACY_ROUND_UP_DN, "Pat Contact"));
        stored.markRead(Instant.now());
        notifications.save(stored);

        MockHttpSession patSession = signIn("pat");
        String before = notificationsOf(patSession);
        assertThat(JsonPath.<String>read(before, "$.content[0].kind")).isEqualTo("EXPIRY_DIGEST");
        assertThat(absentOrNull(before, "$.content[0].subjectName")).isNull();
        assertThat(absentOrNull(before, "$.content[0].link")).isNull();
        assertThat(before).doesNotContain("Pat Contact");
        // The page itself still renders for them.
        mockMvc.perform(get("/notifications").session(patSession)).andExpect(status().isOk());

        runTheRoundUp();

        String after = notificationsOf(patSession);
        assertThat(JsonPath.<Integer>read(after, "$.content.length()")).isEqualTo(1);
        assertThat(JsonPath.<String>read(after, "$.content[0].subjectName")).isEqualTo("web01");
        assertThat(JsonPath.<String>read(after, "$.content[0].link")).isEqualTo("/servers/" + serverId("web01"));
        assertThat(JsonPath.<Boolean>read(after, "$.content[0].unread")).as("nothing new to say").isFalse();
    }

    /**
     * Most certificates cross into expiry because time passes, not because the directory
     * changed - so it is the hourly refresh, not a sweep, that usually notices. A server
     * whose only contacts were added here (it publishes no serverPOC) must still have them
     * told, and the operators' email and the audit trail must still say who they are.
     */
    @Test
    void theHourlyRefreshTellsAServersAddedContactsAndNamesThemToTheOperators() throws Exception {
        person("rhea", "Rhea Added", "rhea@example.gov");
        server("db01", new String[0], TestCertificates.expiringIn("db01.example.gov", Duration.ofSeconds(2)));
        syncService.syncUsers();
        syncService.syncServers();
        Long db01 = serverId("db01");
        contactService.addUser(db01, userId("rhea"), "admin");
        contactService.addEmail(db01, "dba-team@example.gov", "admin");

        Thread.sleep(Duration.ofSeconds(3).toMillis());
        refreshService.refresh();

        String page = notificationsOf(signIn("rhea"));
        assertThat(JsonPath.<Integer>read(page, "$.content.length()")).isEqualTo(1);
        assertThat(JsonPath.<String>read(page, "$.content[0].message"))
                .startsWith("[CRITICAL] Certificate for server db01 expired on ");
        assertThat(JsonPath.<String>read(page, "$.content[0].link")).isEqualTo("/servers/" + db01);
        assertThat(notifications.findAll())
                .as("the unclaimed address is a recipient too")
                .extracting(notification -> notification.getRecipientAddress())
                .containsExactlyInAnyOrder("rhea@example.gov", "dba-team@example.gov");

        outbox.deliverDue();
        List<MimeMessage> operators = to(OPERATORS);
        assertThat(operators).extracting(this::subject).containsExactly("[cert-alert] db01 - EXPIRED");
        assertThat(text(operators.getFirst()))
                .contains("Contact:   ")
                .contains("rhea@example.gov")
                .contains("dba-team@example.gov");
        assertThat(alertsSentAbout(OwnerType.SERVER, db01))
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.getTarget())
                        .contains("rhea@example.gov")
                        .contains("dba-team@example.gov"));
    }

    // ---------------------------------------------------------------------------------

    private void person(String uid, String name, String mail, byte[]... certificates) {
        added.add(directory.addUser(uid, name, mail, certificates));
    }

    private void server(String cn, String[] pocs, byte[]... certificates) {
        added.add(directory.addServer(cn, "https://" + cn + ".example.gov", pocs, certificates));
    }

    /** Null for a field the API leaves out because it has no value, as it does for null. */
    private static Object absentOrNull(String json, String path) {
        return JsonPath.using(Configuration.defaultConfiguration().addOptions(Option.SUPPRESS_EXCEPTIONS))
                .parse(json)
                .read(path);
    }

    /** The round-up, run by an administrator from the notifications page, waited for. */
    private void runTheRoundUp() throws Exception {
        MockHttpSession admin = signIn("admin");
        JobRunsAwait.finished(mockMvc,
                mockMvc.perform(post("/api/v1/notifications/digest").session(admin).with(csrf())),
                request -> {
                    request.setSession(admin);
                    return request;
                });
    }

    private Long userId(String uid) {
        return users.findByDn("uid=" + uid + "," + EmbeddedDirectory.PEOPLE_DN).orElseThrow().getId();
    }

    private Long serverId(String cn) {
        return servers.findByDn("cn=" + cn + "," + EmbeddedDirectory.SERVERS_DN).orElseThrow().getId();
    }

    private MockHttpSession signIn(String uid) throws Exception {
        MockHttpSession session = (MockHttpSession) mockMvc.perform(formLogin().user(uid).password("password"))
                .andReturn()
                .getRequest()
                .getSession(false);
        assertThat(session).as(uid + " signed in").isNotNull();
        return session;
    }

    private String notificationsOf(MockHttpSession session) throws Exception {
        return mockMvc.perform(get("/api/v1/notifications").session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private long unreadCount(MockHttpSession session) throws Exception {
        String body = mockMvc.perform(get("/api/v1/notifications/unread-count").session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.<Map<String, Object>>read(body, "$").get("count")).longValue();
    }

    private List<AuditEvent> alertsSentAbout(OwnerType type, Long id) {
        return auditEvents.findBySubjectTypeAndSubjectIdOrderByOccurredAtDescIdDesc(type, id, PageRequest.of(0, 100))
                .getContent()
                .stream()
                .filter(event -> event.getAction() == AuditAction.ALERT_SENT)
                .toList();
    }

    private List<MimeMessage> to(String address) {
        return mailSender.sent.stream()
                .filter(message -> {
                    try {
                        Address[] recipients = message.getAllRecipients();
                        return recipients != null
                                && List.of(recipients).stream().anyMatch(r -> r.toString().equals(address));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
    }

    private String subject(MimeMessage message) {
        try {
            return message.getSubject();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String text(MimeMessage message) {
        return part(message, "text/plain");
    }

    private String html(MimeMessage message) {
        return part(message, "text/html");
    }

    private String part(Part part, String contentType) {
        try {
            if (part.isMimeType(contentType)) {
                return part.getContent().toString();
            }
            if (part.getContent() instanceof MimeMultipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    String found = part(multipart.getBodyPart(i), contentType);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

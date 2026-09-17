package com.winllc.certalert.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.AuditEventRepository;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The details pages, and the audit table each of them carries.
 *
 * <p>The page renders the entry and its certificates; the table fetches its own rows, so
 * both halves are exercised here.
 */
@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(roles = "USER")
class DetailPageTest {

    private static final String AUDIT = "/api/v1/datatables/audit";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectoryUserRepository userRepository;

    @Autowired
    private DirectoryServerRepository serverRepository;

    @Autowired
    private AuditEventRepository auditRepository;

    private MockMvc mockMvc;
    private Long userId;
    private Long serverId;

    @BeforeEach
    void seed() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        auditRepository.deleteAll();
        serverRepository.deleteAll();
        userRepository.deleteAll();

        Instant now = Instant.now();

        DirectoryUser user = new DirectoryUser("uid=alice,ou=people");
        user.setUid("alice");
        user.setDisplayName("Alice Archer");
        user.setCommonName("Alice Archer");
        user.setTitle("Systems Engineer");
        user.setDutyOrganization("Example Agency");
        user.refreshIdentifiers("alice@example.gov");
        user.addCertificate(certificate("CN=alice@example.gov", now.plus(Duration.ofDays(200)), now));
        user.markSynced(now);
        user.refreshCertificateSummary();
        userId = userRepository.save(user).getId();

        DirectoryServer server = new DirectoryServer("cn=web01,ou=servers");
        server.setCommonName("web01");
        server.setServerUrl("https://web01.example.gov");
        server.setAtoStatus("Authorized");
        server.setServerPocs(List.of("alice@example.gov"));
        server.markSynced(now);
        server.refreshCertificateSummary();
        serverId = serverRepository.save(server).getId();

        auditRepository.saveAll(List.of(
                AuditEvent.about(
                                AuditEvent.SubjectRef.of(user),
                                AuditAction.ENTRY_DISCOVERED,
                                "First seen in the directory, publishing 1 certificate",
                                now.minus(Duration.ofHours(3)))
                        .by("sync"),
                AuditEvent.about(
                                AuditEvent.SubjectRef.of(user),
                                AuditAction.ALERT_SENT,
                                "Certificate for user Alice Archer expires in 9 day(s)",
                                now.minus(Duration.ofHours(1)))
                        .by("expiry refresh")
                        .through("log")
                        .to("alice@example.gov"),
                AuditEvent.about(
                                AuditEvent.SubjectRef.of(server),
                                AuditAction.CONTACT_ADDED,
                                "Added duty.desk@example.gov as a point of contact (address)",
                                now.minus(Duration.ofHours(2)))
                        .by("alice")
                        .to("duty.desk@example.gov")));
    }

    @Test
    void aPersonHasAPageShowingTheirEntryAndCertificates() throws Exception {
        mockMvc.perform(get("/users/{id}", userId))
                .andExpect(status().isOk())
                .andExpect(view().name("user-detail"))
                .andExpect(model().attributeExists("user", "certificates", "identifiers"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Alice Archer")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Systems Engineer")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("uid=alice,ou=people")))
                // Its certificate, rendered with the page rather than fetched.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("CN=alice@example.gov")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SHA256withRSA")))
                // And the audit table, pointed at this person.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"audit-table\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-subject-type=\"USER\"")));
    }

    @Test
    void aServerHasOneToo() throws Exception {
        mockMvc.perform(get("/servers/{id}", serverId))
                .andExpect(status().isOk())
                .andExpect(view().name("server-detail"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("web01")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("https://web01.example.gov")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Authorized")))
                // The contacts editor and the audit table both attach to this server.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-server-id=\"" + serverId)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-subject-type=\"SERVER\"")));
    }

    @Test
    void aPageForSomethingThatIsNotThereIsAPageRatherThanAProblemDetail() throws Exception {
        mockMvc.perform(get("/users/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(view().name("not-found"))
                // The page says what was not found, rather than a bare 404.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No directory user with id")));

        mockMvc.perform(get("/servers/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(view().name("not-found"));
    }

    @Test
    void aDetailsPageNeedsSomebodySignedIn() throws Exception {
        mockMvc.perform(get("/users/{id}", userId).with(anonymous()))
                .andExpect(status().is3xxRedirection());
    }

    /**
     * The table on the page shows that entry's records and nobody else's - including in the
     * count, which is what the table prints beneath itself.
     */
    @Test
    void theAuditTableIsScopedToItsSubject() throws Exception {
        mockMvc.perform(post(AUDIT + "?subjectType=USER&subjectId=" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsTotal").value(2))
                .andExpect(jsonPath("$.recordsFiltered").value(2))
                // Newest first, as the page asks for it.
                .andExpect(jsonPath("$.data[0].action").value("ALERT_SENT"))
                .andExpect(jsonPath("$.data[0].label").value("Alert sent"))
                .andExpect(jsonPath("$.data[0].channel").value("log"))
                .andExpect(jsonPath("$.data[0].actor").value("expiry refresh"));

        mockMvc.perform(post(AUDIT + "?subjectType=SERVER&subjectId=" + serverId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsTotal").value(1))
                .andExpect(jsonPath("$.data[0].action").value("CONTACT_ADDED"));
    }

    @Test
    void theAuditTableNarrowsToAKindOfEvent() throws Exception {
        mockMvc.perform(post(AUDIT + "?subjectType=USER&subjectId=" + userId + "&action=ALERT_SENT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].action").value("ALERT_SENT"));
    }

    @Test
    void theAuditTableSearchesWhatIsWritten() throws Exception {
        mockMvc.perform(post(AUDIT + "?subjectType=USER&subjectId=" + userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request("expires"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsTotal").value(2))
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].action").value("ALERT_SENT"));
    }

    /**
     * A search table takes its subject from the request, so it is worth saying out loud that
     * it cannot be pointed at everything at once.
     */
    @Test
    void theAuditTableWillNotServeEverySubjectAtOnce() throws Exception {
        mockMvc.perform(post(AUDIT + "?subjectType=USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request())
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    private String request() {
        return request("");
    }

    /** The smallest DataTables request the audit table sends, ordered newest first. */
    private String request(String search) {
        return """
                {"draw":1,"start":0,"length":10,
                 "columns":[{"data":"occurredAt","name":"occurredAt","searchable":false,"orderable":true,
                             "search":{"value":"","regex":false}},
                            {"data":"summary","name":"summary","searchable":true,"orderable":false,
                             "search":{"value":"","regex":false}}],
                 "order":[{"column":0,"dir":"desc"}],
                 "search":{"value":"%s","regex":false}}"""
                .formatted(search);
    }

    private CachedCertificate certificate(String subject, Instant notAfter, Instant now) {
        return new CachedCertificate(
                String.format("%064x", Math.abs((long) subject.hashCode())),
                "01",
                subject,
                "CN=Example CA",
                now.minus(Duration.ofDays(365)),
                notAfter,
                "SHA256withRSA",
                "SHA-256",
                "RSA",
                2048,
                null,
                now);
    }
}

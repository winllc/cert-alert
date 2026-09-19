package com.winllc.certalert.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.AuditEventRepository;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The administration page and the log on it.
 *
 * <p>One entry's history is everybody's to read: a person looking at a server can see what
 * has been done to it. The whole trail at once is a different thing - read end to end it
 * says who has been here, what they touched and when - and it is an administrator's.
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminPageTest {

    private static final String AUDIT = "/api/v1/datatables/audit";
    private static final String SUMMARY = "/api/v1/audit/summary";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AuditEventRepository auditRepository;

    @Autowired
    private DirectoryUserRepository userRepository;

    @Autowired
    private DirectoryServerRepository serverRepository;

    private MockMvc mockMvc;
    private Instant now;
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

        now = Instant.now();

        DirectoryUser alice = new DirectoryUser("uid=alice,ou=people");
        alice.setUid("alice");
        alice.setDisplayName("Alice Archer");
        alice.refreshIdentifiers("alice@example.gov");
        alice.markSynced(now);
        userId = userRepository.save(alice).getId();

        DirectoryServer web01 = new DirectoryServer("cn=web01,ou=servers");
        web01.setCommonName("web01");
        web01.markSynced(now);
        serverId = serverRepository.save(web01).getId();

        auditRepository.saveAll(List.of(
                AuditEvent.about(
                                AuditEvent.SubjectRef.of(alice),
                                AuditAction.ENTRY_DISCOVERED,
                                "First seen in the directory",
                                now.minus(Duration.ofHours(2)))
                        .by("sync"),
                AuditEvent.about(
                                AuditEvent.SubjectRef.of(web01),
                                AuditAction.CONTACT_ADDED,
                                "Added duty.desk@example.gov as a point of contact",
                                now.minus(Duration.ofHours(1)))
                        .by("alice")
                        .to("duty.desk@example.gov"),
                AuditEvent.about(
                                AuditEvent.SubjectRef.of(web01),
                                AuditAction.ALERT_SENT,
                                "Certificate for web01 expires in 9 day(s)",
                                now.minus(Duration.ofDays(30)))
                        .by("expiry refresh")
                        .through("log")
                        .to("alice@example.gov")));
    }

    @Test
    void theAdministrationPageCarriesTheWholeTrail() throws Exception {
        mockMvc.perform(get("/admin").with(admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("System audit log")))
                // The kinds of event come from the enum, so a new one appears here by itself.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PROJECT_JOINED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"audit-table\"")));
    }

    @Test
    void aReaderHasNoAdministrationPage() throws Exception {
        mockMvc.perform(get("/admin").with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin").with(anonymous())).andExpect(status().is3xxRedirection());
    }

    /** Every subject at once, which is what makes it the system log rather than a history. */
    @Test
    void theLogShowsEveryEntryAtOnce() throws Exception {
        mockMvc.perform(post(AUDIT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request())
                        .with(csrf())
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsTotal").value(3))
                .andExpect(jsonPath("$.recordsFiltered").value(3))
                // Newest first, and each row says what it is about.
                .andExpect(jsonPath("$.data[0].action").value("CONTACT_ADDED"))
                .andExpect(jsonPath("$.data[0].subjectType").value("SERVER"))
                .andExpect(jsonPath("$.data[0].subjectName").value("web01"))
                .andExpect(jsonPath("$.data[0].subjectId").value(serverId))
                .andExpect(jsonPath("$.data[1].subjectName").value("Alice Archer"));
    }

    @Test
    void aReaderIsRefusedTheWholeTrail() throws Exception {
        mockMvc.perform(post(AUDIT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request())
                        .with(csrf())
                        .with(reader()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Not allowed"));
    }

    /** And the table on a details page is unchanged by any of it. */
    @Test
    void aReaderStillSeesOneEntrysHistory() throws Exception {
        mockMvc.perform(post(AUDIT + "?subjectType=SERVER&subjectId=" + serverId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request())
                        .with(csrf())
                        .with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsTotal").value(2));
    }

    @Test
    void theLogNarrowsToAKindOfEventAndToWhatItIsAbout() throws Exception {
        mockMvc.perform(post(AUDIT + "?action=ALERT_SENT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request())
                        .with(csrf())
                        .with(admin()))
                .andExpect(status().isOk())
                // The count of everything is the whole trail, so the table can say what it
                // narrowed from.
                .andExpect(jsonPath("$.recordsTotal").value(3))
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].channel").value("log"));

        mockMvc.perform(post(AUDIT + "?subjectType=USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request())
                        .with(csrf())
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].subjectName").value("Alice Archer"));
    }

    @Test
    void theLogNarrowsToWhoDidIt() throws Exception {
        mockMvc.perform(post(AUDIT + "?actor=alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request())
                        .with(csrf())
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].actor").value("alice"));

        // The jobs are actors too, and are matched on part of the name like anybody else.
        mockMvc.perform(post(AUDIT + "?actor=refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request())
                        .with(csrf())
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].actor").value("expiry refresh"));
    }

    @Test
    void theLogNarrowsToWhenItHappened() throws Exception {
        // Taken from the records rather than from today, so the test says the same thing
        // when it runs just after midnight as when it runs at noon.
        String recent = day(now.minus(Duration.ofHours(2)));
        String longAgo = day(now.minus(Duration.ofDays(30)));

        mockMvc.perform(post(AUDIT + "?from=" + recent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request())
                        .with(csrf())
                        .with(admin()))
                .andExpect(status().isOk())
                // The two from a couple of hours ago; the alert was a month back.
                .andExpect(jsonPath("$.recordsFiltered").value(2));

        mockMvc.perform(post(AUDIT + "?to=" + longAgo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request())
                        .with(csrf())
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].action").value("ALERT_SENT"));
    }

    /** The box under the About column searches the name and the DN at once. */
    @Test
    void theLogSearchesWhatARecordIsAbout() throws Exception {
        mockMvc.perform(post(AUDIT + "?subject=ou=servers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request())
                        .with(csrf())
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(2));

        mockMvc.perform(post(AUDIT + "?subject=archer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request())
                        .with(csrf())
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1));
    }

    @Test
    void theSummaryCountsTheTrailAndSaysHowFarBackItGoes() throws Exception {
        mockMvc.perform(get(SUMMARY).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.lastDay").value(2))
                .andExpect(jsonPath("$.lastWeek").value(2))
                .andExpect(jsonPath("$.actors").value(3))
                .andExpect(jsonPath("$.oldest").exists())
                .andExpect(jsonPath("$.newest").exists());
    }

    @Test
    void theSummaryIsAdministratorsToo() throws Exception {
        mockMvc.perform(get(SUMMARY).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get(SUMMARY).with(anonymous())).andExpect(status().isUnauthorized());
    }

    /** The UTC day an instant falls on, as the date inputs send it. */
    private String day(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC).toString();
    }

    private RequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors.user("root").roles("USER", "ADMIN");
    }

    private RequestPostProcessor reader() {
        return SecurityMockMvcRequestPostProcessors.user("reader").roles("USER");
    }

    /** The smallest DataTables request the log sends, ordered newest first. */
    private String request() {
        return """
                {"draw":1,"start":0,"length":10,
                 "columns":[{"data":"occurredAt","name":"occurredAt","searchable":false,"orderable":true,
                             "search":{"value":"","regex":false}},
                            {"data":"subjectName","name":"subjectName","searchable":true,"orderable":true,
                             "search":{"value":"","regex":false}},
                            {"data":"summary","name":"summary","searchable":true,"orderable":false,
                             "search":{"value":"","regex":false}}],
                 "order":[{"column":0,"dir":"desc"}],
                 "search":{"value":"","regex":false}}""";
    }
}

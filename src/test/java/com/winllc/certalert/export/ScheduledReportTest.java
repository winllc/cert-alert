package com.winllc.certalert.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.audit.AuditStore;
import com.winllc.certalert.audit.StoredEvent;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.OutboundEmail;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.OutboundEmailRepository;
import com.winllc.certalert.repository.ServerContactRepository;
import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.security.DirectoryPrincipalResolver;
import com.winllc.certalert.support.EmbeddedDirectory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Scheduled reports: off until the recipient domains are named; scheduled by an administrator
 * the directory holds; emailed with their file on schedule; and checked again at every run -
 * an owner no longer an administrator, deleted or locked, or a recipient now outside the
 * domains, and nothing is sent.
 */
@SpringBootTest(properties = "cert-alert.security.groups.admin[0]=cn=pki-admins,ou=groups,dc=example,dc=test")
@ActiveProfiles("test")
class ScheduledReportTest {

    private static final String ADMIN_GROUP = "cn=pki-admins,ou=groups," + EmbeddedDirectory.BASE_DN;

    private static EmbeddedDirectory directory;

    @BeforeAll
    static void startDirectory() {
        directory = new EmbeddedDirectory();
    }

    @AfterAll
    static void stopDirectory() {
        directory.close();
    }

    @DynamicPropertySource
    static void directoryProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", () -> directory.url());
        registry.add("spring.ldap.base", () -> EmbeddedDirectory.BASE_DN);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectoryServerRepository servers;

    @Autowired
    private ServerContactRepository contacts;

    @Autowired
    private OutboundEmailRepository outbox;

    @Autowired
    private ScheduledReports reports;

    @Autowired
    private ExportProperties properties;

    @Autowired
    private AuditStore audit;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mockMvc;
    private static int people;

    @BeforeEach
    void seed() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        contacts.deleteAll();
        servers.deleteAll();
        outbox.deleteAll();
        Instant now = Instant.now();
        CachedCertificate weak = new CachedCertificate("c".repeat(64), "0A", "CN=legacy01", "CN=Old CA",
                now.minus(Duration.ofDays(300)), now.plus(Duration.ofDays(12)), "SHA1withRSA", "SHA-1", "RSA", 1024, null, now);
        weak.updateStatus(CertificateStatus.EXPIRING_SOON, now);
        DirectoryServer server = new DirectoryServer("cn=legacy01,ou=servers");
        server.setCommonName("legacy01");
        server.addCertificate(weak);
        server.markSynced(now);
        server.refreshCertificateSummary();
        servers.save(server);
        properties.setReportRecipientDomains(List.of("example.gov"));
    }

    @AfterEach
    void restore() {
        properties.setReportRecipientDomains(List.of());
        jdbc.update("DELETE FROM scheduled_report");
    }

    /** An administrator by a directory group, signed in. */
    private Authentication administrator() {
        String uid = "admin" + (++people);
        String dn = directory.addUser(uid, "Report Admin", uid + "@example.gov");
        directory.modify(dn, "memberOf", ADMIN_GROUP);
        List<SimpleGrantedAuthority> roles = List.of(
                new SimpleGrantedAuthority(DirectoryPrincipalResolver.ROLE_USER),
                new SimpleGrantedAuthority(DirectoryPrincipalResolver.ROLE_ADMIN));
        return new UsernamePasswordAuthenticationToken(
                new DirectoryPrincipal(uid, null, null, dn, DirectoryPrincipal.AuthenticationMethod.LDAP, roles), null, roles);
    }

    private static String dnOf(Authentication authentication) {
        return ((DirectoryPrincipal) authentication.getPrincipal()).getDirectoryDn();
    }

    private static final String WEEKLY = "{\"name\":\"Weekly weak\",\"query\":\"hashAlgorithm=SHA-1\",\"format\":\"xlsx\","
            + "\"recipients\":[\"pki@example.gov, ops@example.gov\"],\"frequency\":\"WEEKLY\",\"dayOfWeek\":1,\"hourUtc\":6}";

    private Long schedule(Authentication owner) throws Exception {
        String created = mockMvc.perform(post(ScheduledReportController.PATH).with(authentication(owner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(WEEKLY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recipients.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        return Long.valueOf(created.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private void due(Long id) {
        jdbc.update("UPDATE scheduled_report SET next_run_at = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(60)), id);
    }

    private long reportEmails() {
        return outbox.findAll().stream().filter(message -> message.getKind() == OutboundEmail.Kind.REPORT).count();
    }

    @Test
    void nothingCanBeScheduledUntilTheRecipientDomainsAreNamed() throws Exception {
        properties.setReportRecipientDomains(List.of());
        mockMvc.perform(post(ScheduledReportController.PATH).with(authentication(administrator())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(WEEKLY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("report-recipient-domains")));
    }

    @Test
    void onlyAnAdministratorTheDirectoryHoldsSchedulesOneAndOnlyInsideTheDomains() throws Exception {
        mockMvc.perform(post(ScheduledReportController.PATH).with(SecurityMockMvcRequestPostProcessors.user("r").roles("USER"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(WEEKLY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(ScheduledReportController.PATH).with(SecurityMockMvcRequestPostProcessors.user("root").roles("USER", "ADMIN"))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(WEEKLY))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(ScheduledReportController.PATH).with(authentication(administrator())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(WEEKLY.replace("ops@example.gov", "ops@elsewhere.example")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aReportIsEmailedWithItsFileOnSchedule() throws Exception {
        Long id = schedule(administrator());
        assertThat(reports.runDue()).as("not due until Monday 06:00").isZero();
        due(id);
        long head = audit.headSeq();
        assertThat(reports.runDue()).isEqualTo(1);

        OutboundEmail email = outbox.findAll().stream()
                .filter(message -> message.getKind() == OutboundEmail.Kind.REPORT).findFirst().orElseThrow();
        assertThat(email.getRecipients()).containsExactly("pki@example.gov", "ops@example.gov");
        assertThat(email.getSubject()).contains("Weekly weak", "1 certificate");
        assertThat(email.getAttachmentName()).endsWith(".xlsx");
        assertThat(unzip(email.getAttachment()).get("xl/worksheets/sheet1.xml")).contains("legacy01");
        assertThat(reports.find(id).getNextRunAt()).isAfter(Instant.now());
        assertThat(reports.find(id).getLastRows()).isEqualTo(1);
        assertThat(audit.after(head, 100)).extracting(StoredEvent::action).contains("report.sent");
    }

    @Test
    void anOwnerNoLongerAnAdministratorSendsNothing() throws Exception {
        Authentication owner = administrator();
        Long id = schedule(owner);
        directory.modify(dnOf(owner), "memberOf");
        due(id);
        assertThat(reports.runDue()).isZero();
        assertThat(reportEmails()).isZero();
        assertThat(reports.find(id).getLastError()).contains("no longer an administrator");
    }

    @Test
    void aLockedOrDeletedOwnerSendsNothing() throws Exception {
        Authentication locked = administrator();
        Long first = schedule(locked);
        directory.modify(dnOf(locked), "nsAccountLock", "true");
        Authentication deleted = administrator();
        Long second = schedule(deleted);
        directory.delete(dnOf(deleted));
        due(first);
        due(second);
        long head = audit.headSeq();
        assertThat(reports.runDue()).isZero();
        assertThat(reportEmails()).isZero();
        assertThat(reports.find(first).getLastError()).contains("locked");
        assertThat(reports.find(second).getLastError()).contains("no longer in the directory");
        assertThat(audit.after(head, 100)).filteredOn(event -> event.action().equals("report.sent"))
                .hasSize(2).allSatisfy(event -> assertThat(event.line()).contains("\"outcome\":\"failure\""));
    }

    @Test
    void aRecipientNowOutsideTheDomainsStopsTheReport() throws Exception {
        Long id = schedule(administrator());
        properties.setReportRecipientDomains(List.of("other.example"));
        due(id);
        assertThat(reports.runDue()).isZero();
        assertThat(reportEmails()).isZero();
        assertThat(reports.find(id).getLastError()).contains("pki@example.gov is outside");
    }

    private static Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }
}

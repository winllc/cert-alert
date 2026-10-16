package com.winllc.certalert.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enssolutions.license.LicenseManager;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestLicenses;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * What must be audited, end to end through the real filter chain: signing in and failing to,
 * a refusal, a settings change, a license install, an export - and that a password or a
 * token never reaches any output.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditCoverageTest {

    /** Distinctive, so finding it anywhere is unambiguous. */
    private static final String WRONG_PASSWORD = "Wr0ng-S3cret-Passw0rd-7781";

    private static EmbeddedDirectory directory;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AuditStore store;

    @Autowired
    private AccessDenials denials;

    @Autowired
    private LicenseManager licenses;

    @Autowired
    private AuditDeliveryService delivery;

    @Autowired
    private AuditLog audit;

    @Autowired
    private com.winllc.certalert.service.DirectorySyncService syncService;

    @Autowired
    private com.winllc.certalert.repository.DirectoryUserRepository users;

    @Autowired
    private com.winllc.certalert.repository.NotificationSettingRepository notificationSettings;

    private MockMvc mockMvc;
    private AuditLines output;
    private long head;

    @BeforeAll
    static void startDirectory() {
        directory = new EmbeddedDirectory();
        directory.addUser("alice", "Alice Archer", "alice@example.gov");
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
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        if (users.findByDn("uid=alice," + EmbeddedDirectory.PEOPLE_DN).isEmpty()) {
            syncService.syncUsers();
        }
        denials.flush();
        head = store.headSeq();
        output = new AuditLines();
    }

    @AfterEach
    void tearDown() {
        output.close();
        TestLicenses.restoreFull(licenses);
    }

    private List<StoredEvent> written() {
        return store.after(head, 1000);
    }

    private StoredEvent only(String action) {
        List<StoredEvent> matching = written().stream().filter(e -> action.equals(e.action())).toList();
        assertThat(matching).as("events %s in %s", action, written()).hasSize(1);
        return matching.getFirst();
    }

    private static RequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors.user("root").roles("USER", "ADMIN");
    }

    private static RequestPostProcessor reader() {
        return SecurityMockMvcRequestPostProcessors.user("reader").roles("USER");
    }

    @Test
    void aSignInIsRecordedWithItsMethodAndWhoItWas() throws Exception {
        mockMvc.perform(formLogin().user("alice").password("password")).andExpect(status().is3xxRedirection());

        StoredEvent login = only("auth.login");
        assertThat(login.line()).contains(
                "\"category\":\"authentication\"", "\"outcome\":\"success\"", "\"severity\":\"info\"",
                "\"actor\":{\"type\":\"user\",\"id\":\"alice@example.gov\",\"name\":\"Alice Archer\",\"roles\":[\"USER\"]}",
                "\"source\":{\"ip\":\"127.0.0.1\"", "\"method\":\"ldap\"");
        assertThat(output.all()).contains(login.line());
    }

    @Test
    void aFailedSignInIsRecordedAgainstTheAttemptedNameWithoutThePassword() throws Exception {
        mockMvc.perform(formLogin().user("alice").password(WRONG_PASSWORD)).andExpect(status().is3xxRedirection());

        StoredEvent login = only("auth.login");
        assertThat(login.line()).contains(
                "\"outcome\":\"failure\"", "\"severity\":\"warning\"",
                "\"actor\":{\"type\":\"anonymous\",\"id\":\"alice\",\"name\":null,\"roles\":[]}",
                "\"reason\":\"bad credentials\"", "\"method\":\"ldap\"");
        assertNoSecret(WRONG_PASSWORD);
    }

    @Test
    void aSignOutIsRecorded() throws Exception {
        mockMvc.perform(post("/logout").with(reader()).with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(only("auth.logout").line()).contains("\"actor\":{\"type\":\"user\",\"id\":\"reader\"");
    }

    @Test
    void aRefusalIsRecordedWithMethodPathAndReasonAndRepeatsAreCollapsed() throws Exception {
        mockMvc.perform(get("/admin").with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin").with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin").with(reader())).andExpect(status().isForbidden());
        denials.flush();

        StoredEvent denied = only("access.denied");
        assertThat(denied.line()).contains(
                "\"category\":\"authorization\"", "\"outcome\":\"denied\"", "\"severity\":\"warning\"",
                "\"actor\":{\"type\":\"user\",\"id\":\"reader\"", "\"method\":\"GET\"", "\"path\":\"/admin\"",
                "\"reasonCode\":\"role_required\"", "\"count\":3");
    }

    @Test
    void aMissingCsrfTokenIsRecordedAsADenial() throws Exception {
        mockMvc.perform(post("/api/v1/sync/users").with(admin())).andExpect(status().isForbidden());
        denials.flush();
        assertThat(only("access.denied").line()).contains("\"reasonCode\":\"csrf\"");
    }

    @Test
    void aLicenseRefusalIsRecordedWithItsCode() throws Exception {
        TestLicenses.uninstall(licenses);
        mockMvc.perform(get("/api/v1/stats/users").with(reader()).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
        denials.flush();
        assertThat(only("access.denied").line()).contains("\"reasonCode\":\"license_required\"");
        // And the license going away was noticed as a change of mode.
        assertThat(written()).extracting(StoredEvent::action).contains("license.mode-changed");
    }

    @Test
    void aSettingsChangeRecordsTheOldAndNewValues() throws Exception {
        mockMvc.perform(put("/api/v1/notifications/settings").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"leadDays\":21}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/notifications/settings").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"leadDays\":17}"))
                .andExpect(status().isOk());

        List<StoredEvent> changes = written().stream().filter(e -> "settings.changed".equals(e.action())).toList();
        assertThat(changes).hasSize(2);
        assertThat(changes.get(1).line()).contains(
                "\"category\":\"configuration\"", "\"severity\":\"notice\"", "\"actor\":{\"type\":\"user\",\"id\":\"root\"",
                "\"leadDays.old\":21", "\"leadDays.new\":17");
        // The database is shared by every test class; put the setting back as it was.
        notificationSettings.deleteAll();
    }

    @Test
    void installingALicenseIsRecordedAndSoIsARefusedOne() throws Exception {
        String license = TestLicenses.license().id("lic_audit_install_0001").sign();
        mockMvc.perform(post("/admin/license").with(admin()).with(csrf()).param("text", license))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/admin/license").with(admin()).with(csrf()).param("text", "not a license"))
                .andExpect(status().is3xxRedirection());

        StoredEvent installed = only("license.installed");
        assertThat(installed.line()).contains("\"category\":\"license\"", "\"severity\":\"notice\"",
                "\"licenseId\":\"lic_audit_install_0001\"", "\"target\":{\"type\":\"license\"");
        assertThat(only("license.rejected").line()).contains("\"outcome\":\"failure\"", "not a valid ENS license");
        // The license text itself is never written down.
        assertNoSecret(license.substring(40, 80));
    }

    @Test
    void anExportIsRecordedWithItsFilterAndCount() throws Exception {
        audit.record(AuditRecord.of(AuditRecord.Category.DATA, "test.exportable").actor(AuditRecord.Actor.system("t")));
        mockMvc.perform(get("/api/v1/admin/audit/export").param("format", "csv").param("action", "test.exportable")
                        .with(admin()))
                .andExpect(status().isOk());

        assertThat(only("audit.exported").line()).contains(
                "\"category\":\"audit\"", "\"format\":\"csv\"", "\"filter\":\"action=test.exportable\"", "\"count\":1");
    }

    @Test
    void aTokenNeverReachesAnyOutput() throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/audit/tokens").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"siem\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String secret = body.replaceAll(".*\"secret\":\"([^\"]+)\".*", "$1");
        assertThat(secret).startsWith(AuditTokens.PREFIX);
        // Even somebody putting one into a detail by mistake.
        audit.record(AuditRecord.of(AuditRecord.Category.CONFIGURATION, "test.secrets")
                .detail("token", secret)
                .detail("password", WRONG_PASSWORD)
                .detail("note", "pasted " + secret));

        assertThat(only("audit.token-created").line()).contains("\"prefix\":\"" + secret.substring(0, 12) + "\"");
        assertNoSecret(secret);
        assertNoSecret(WRONG_PASSWORD);
    }

    /** Not in the store, not on stdout or in the file, not to Splunk or syslog, not in an export. */
    private void assertNoSecret(String secret) throws Exception {
        List<StoredEvent> events = written();
        assertThat(events).isNotEmpty();
        for (StoredEvent event : events) {
            assertThat(event.line()).doesNotContain(secret);
        }
        assertThat(output.all()).doesNotContain(secret);
        EnsAuditProperties.Splunk splunk = new EnsAuditProperties.Splunk();
        splunk.setUrl("https://splunk.invalid:8088");
        splunk.setToken("hec-token");
        assertThat(new SplunkHecForwarder(splunk, "h", "certalert").payload(events)).doesNotContain(secret);
        EnsAuditProperties.Syslog syslog = new EnsAuditProperties.Syslog();
        syslog.setHost("syslog.invalid");
        SyslogForwarder forwarder = new SyslogForwarder(syslog, "h", "certalert");
        for (StoredEvent event : events) {
            assertThat(forwarder.message(event)).doesNotContain(secret);
        }
        for (String format : List.of("csv", "ndjson")) {
            String export = mockMvc.perform(get("/api/v1/admin/audit/export").param("format", format).with(admin()))
                    .andReturn().getResponse().getContentAsString();
            assertThat(export).doesNotContain(secret);
        }
        assertThat(delivery).isNotNull();
    }
}

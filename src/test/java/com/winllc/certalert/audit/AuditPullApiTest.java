package com.winllc.certalert.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enssolutions.license.LicenseManager;
import com.winllc.certalert.support.TestLicenses;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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
 * The SIEM pull API: a token reads the trail after a cursor, as NDJSON or JSON; no token, a
 * wrong one and a revoked one get 401; and it keeps working while the application is
 * unlicensed or read-only - as do the audit page, its export and verification.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditPullApiTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AuditLog audit;

    @Autowired
    private AuditStore store;

    @Autowired
    private AuditTokens tokens;

    @Autowired
    private LicenseManager licenses;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @AfterEach
    void restore() {
        TestLicenses.restoreFull(licenses);
    }

    private static RequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors.user("root").roles("USER", "ADMIN");
    }

    private String newToken() throws Exception {
        String body = mockMvc.perform(post("/api/v1/admin/audit/tokens").with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"pull test\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"secret\":\"([^\"]+)\".*", "$1");
    }

    private void record(String action) {
        audit.record(AuditRecord.of(AuditRecord.Category.SYSTEM, action).actor(AuditRecord.Actor.system("t")));
    }

    @Test
    void aTokenReadsTheEventsAfterACursorAsNdjson() throws Exception {
        String token = newToken();
        long cursor = store.headSeq();
        record("test.pull.one");
        record("test.pull.two");

        String body = mockMvc.perform(get("/api/audit/events").param("after", String.valueOf(cursor))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
                .andReturn().getResponse().getContentAsString();
        List<String> lines = Arrays.stream(body.split("\n")).filter(l -> !l.isBlank()).toList();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).contains("\"seq\":" + (cursor + 1), "\"action\":\"test.pull.one\"");
        assertThat(lines.get(1)).contains("\"seq\":" + (cursor + 2), "\"action\":\"test.pull.two\"");
        assertThat(lines.get(1)).isEqualTo(store.after(cursor + 1, 1).getFirst().line());

        // A pull is not itself audited; the token's last use is recorded instead.
        assertThat(store.headSeq()).isEqualTo(cursor + 2);
        assertThat(tokens.list().stream().filter(t -> t.getPrefix().equals(token.substring(0, 12))).findFirst()
                .orElseThrow().getLastUsedAt()).isNotNull();
    }

    @Test
    void asJsonItSaysWhereToAskFromNext() throws Exception {
        String token = newToken();
        long cursor = store.headSeq();
        record("test.pull.json");
        record("test.pull.json");
        record("test.pull.json");

        mockMvc.perform(get("/api/audit/events").param("after", String.valueOf(cursor)).param("limit", "2")
                        .header("Authorization", "Bearer " + token).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(2))
                .andExpect(jsonPath("$.events[0].seq").value(cursor + 1))
                .andExpect(jsonPath("$.events[0].schema").value("ens-audit/1"))
                .andExpect(jsonPath("$.next").value(cursor + 2));
    }

    @Test
    void noTokenAWrongOneOrASessionGets401() throws Exception {
        mockMvc.perform(get("/api/audit/events")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/audit/events").header("Authorization", "Bearer ensat_not-a-real-token"))
                .andExpect(status().isUnauthorized());
        // A signed-in administrator's session is not a token either.
        mockMvc.perform(get("/api/audit/events").with(admin())).andExpect(status().isUnauthorized());
    }

    @Test
    void aRevokedTokenGets401AndRevokingIsAudited() throws Exception {
        String token = newToken();
        mockMvc.perform(get("/api/audit/events").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        Long id = tokens.list().stream().filter(t -> t.getPrefix().equals(token.substring(0, 12))).findFirst()
                .orElseThrow().getId();
        long cursor = store.headSeq();

        mockMvc.perform(delete("/api/v1/admin/audit/tokens/{id}", id).with(admin()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revokedBy").value("root"));

        mockMvc.perform(get("/api/audit/events").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        assertThat(store.after(cursor, 10)).extracting(StoredEvent::action).containsExactly("audit.token-revoked");
    }

    @Test
    void itKeepsWorkingWhileUnlicensedAndSoDoesTheAuditPage() throws Exception {
        String token = newToken();
        TestLicenses.uninstall(licenses);

        mockMvc.perform(get("/api/audit/events").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/audit").with(admin())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/audit/events").with(admin())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/audit/export").param("format", "ndjson").with(admin()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/audit/verify").with(admin()).with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void andWhileReadOnly() throws Exception {
        String token = newToken();
        TestLicenses.install(licenses, TestLicenses.license().expired().sign());

        mockMvc.perform(get("/api/audit/events").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/audit/verify").with(admin()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/audit/export").param("format", "csv").with(admin()))
                .andExpect(status().isOk());
    }
}

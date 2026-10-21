package com.winllc.certalert.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.winllc.certalert.audit.AuditStore;
import com.winllc.certalert.audit.StoredEvent;
import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.security.DirectoryPrincipalResolver;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The public API's tokens, end to end through the real filter chains: created by an
 * administrator and shown once; scopes enforced endpoint by endpoint; revoked, expired and
 * out-ranked tokens refused; no session and no CSRF for a token, and CSRF still required of
 * a session; and the token never in a log, the audit trail or the session.
 */
@SpringBootTest
@ActiveProfiles("test")
class ApiTokenTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ApiTokens tokens;

    @Autowired
    private AuditStore audit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEventPublisher events;

    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> logs;
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(logs);
        logs.stop();
    }

    private static RequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors.user("root").roles("USER", "ADMIN");
    }

    private String create(String scopes) throws Exception {
        return create(scopes, admin(), null);
    }

    private String create(String scopes, RequestPostProcessor who, MockHttpSession session) throws Exception {
        String list = java.util.Arrays.stream(scopes.split(","))
                .map(scope -> "\"" + scope + "\"").collect(Collectors.joining(","));
        var request = post(ApiTokenAdminController.PATH).with(who).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"test " + scopes + "\",\"scopes\":[" + list + "]}");
        if (session != null) {
            request.session(session);
        }
        String body = mockMvc.perform(request)
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        String secret = body.replaceAll(".*\"secret\":\"([^\"]+)\".*", "$1");
        assertThat(secret).startsWith(ApiTokens.PREFIX).hasSize(ApiTokens.PREFIX.length() + 43);
        return secret;
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void aTokenReadsTheInventoryWithoutASessionOrCsrf() throws Exception {
        String token = create("certificates:read");

        MvcResult result = mockMvc.perform(get("/api/v1/certificates").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andReturn();
        // Stateless: no session made, no cookie set.
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeader("Set-Cookie")).isNull();

        mockMvc.perform(get("/api/v1/summary/expiry").header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byStatus.VALID").isNumber())
                .andExpect(jsonPath("$.expiringWithinDays.30").isNumber());
        mockMvc.perform(get("/api/v1/servers").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
    }

    @Test
    void scopesAreEnforcedEndpointByEndpoint() throws Exception {
        String reader = create("certificates:read");

        // People need people:read; jobs need jobs:read; starting one needs jobs:run.
        mockMvc.perform(get("/api/v1/people").header("Authorization", bearer(reader)))
                .andExpect(status().isForbidden())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("insufficient_scope")));
        mockMvc.perform(get("/api/v1/jobs").header("Authorization", bearer(reader)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/revocation/check").header("Authorization", bearer(reader)))
                .andExpect(status().isForbidden());

        String runner = create("jobs:run");
        mockMvc.perform(post("/api/v1/revocation/check").header("Authorization", bearer(runner)))
                .andExpect(status().isAccepted());
        // jobs:run implies jobs:read, so the run can be asked after.
        mockMvc.perform(get("/api/v1/jobs").header("Authorization", bearer(runner)))
                .andExpect(status().isOk());
        // ... and nothing else.
        mockMvc.perform(get("/api/v1/certificates").header("Authorization", bearer(runner)))
                .andExpect(status().isForbidden());
    }

    @Test
    void evenAnAdminTokenCannotManageTokensWebhooksOrBeAPerson() throws Exception {
        String admin = create("admin");
        mockMvc.perform(get("/api/v1/certificates").header("Authorization", bearer(admin))).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/audit/events").header("Authorization", bearer(admin))).andExpect(status().isOk());

        for (String path : List.of(ApiTokenAdminController.PATH, "/api/v1/admin/webhooks", "/api/v1/admin/audit/tokens",
                "/api/v1/notifications")) {
            mockMvc.perform(get(path).header("Authorization", bearer(admin)))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(post(ApiTokenAdminController.PATH).header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"minted\",\"scopes\":[\"admin\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownRevokedAndExpiredTokensAreRefusedAndTheRefusalAudited() throws Exception {
        mockMvc.perform(get("/api/v1/certificates").header("Authorization", bearer("cat_not-a-real-token-at-all-000000000000")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.containsString("invalid_token")));

        String revoked = create("certificates:read");
        Long id = idOf(revoked);
        mockMvc.perform(delete(ApiTokenAdminController.PATH + "/" + id).with(admin()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("revoked"));
        long head = audit.headSeq();
        mockMvc.perform(get("/api/v1/certificates").header("Authorization", bearer(revoked)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("This API token has been revoked"));
        assertThat(actions(head)).contains("api.token-refused");

        String expired = create("certificates:read");
        jdbc.update("UPDATE api_token SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), idOf(expired));
        mockMvc.perform(get("/api/v1/certificates").header("Authorization", bearer(expired)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("This API token has expired"));
    }

    @Test
    void aSessionCookieDoesNotStandInForABadToken() throws Exception {
        // An administrator's session, and a token that is no good: the token decides.
        mockMvc.perform(get("/api/v1/certificates").with(admin())
                        .header("Authorization", bearer("cat_not-a-real-token-at-all-000000000000")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aSessionStillNeedsItsCsrfTokenForTheSameEndpoints() throws Exception {
        mockMvc.perform(post("/api/v1/revocation/check").with(admin()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/revocation/check").with(admin()).with(csrf()))
                .andExpect(status().isAccepted());
    }

    @Test
    void onlyAnAdministratorCreatesATokenAndOnlyWithScopesTheirRolesAllow() throws Exception {
        RequestPostProcessor auditor = SecurityMockMvcRequestPostProcessors.user("aud").roles("USER", "AUDITOR");
        mockMvc.perform(post(ApiTokenAdminController.PATH).with(auditor).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"scopes\":[\"jobs:read\"]}"))
                .andExpect(status().isForbidden());
        // An auditor may list them; the list carries no secret and no hash.
        String body = mockMvc.perform(get(ApiTokenAdminController.PATH).with(auditor))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("tokenHash").doesNotContain("\"secret\"");

        mockMvc.perform(post(ApiTokenAdminController.PATH).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\",\"scopes\":[\"everything\"]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(ApiTokenAdminController.PATH).with(admin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"scopes\":[\"certificates:read\"],\"expiresAt\":\"2001-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aTokenLosesWhatItsCreatorLosesAtTheirNextSignIn() throws Exception {
        Authentication creator = new UsernamePasswordAuthenticationToken("dana", null, List.of(
                new SimpleGrantedAuthority(DirectoryPrincipalResolver.ROLE_USER),
                new SimpleGrantedAuthority(DirectoryPrincipalResolver.ROLE_ADMIN)));
        String runner = create("jobs:run", authentication(creator), null);
        String reader = create("certificates:read", authentication(creator), null);
        mockMvc.perform(get("/api/v1/jobs").header("Authorization", bearer(runner))).andExpect(status().isOk());

        // Dana signs in again, no longer an administrator.
        events.publishEvent(new AuthenticationSuccessEvent(new UsernamePasswordAuthenticationToken("dana", null,
                List.of(new SimpleGrantedAuthority(DirectoryPrincipalResolver.ROLE_USER)))));

        mockMvc.perform(get("/api/v1/jobs").header("Authorization", bearer(runner)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("no longer holds")));
        mockMvc.perform(get("/api/v1/certificates").header("Authorization", bearer(reader)))
                .andExpect(status().isOk());

        // Signing in as an administrator again does not give the token back what it lost.
        events.publishEvent(new AuthenticationSuccessEvent(creator));
        mockMvc.perform(get("/api/v1/jobs").header("Authorization", bearer(runner)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aDirectoryCreatorsRolesAreWorkedOutAgainAsTheTokenIsUsed() throws Exception {
        // Bob held the administrator role when he made the tokens - by a group, say - but the
        // directory and the configuration (admin-identifiers: [admin]) say he is a reader now.
        Authentication bob = signedIn("uid=bob,ou=people,dc=example,dc=gov");
        String bobs = create("jobs:read", authentication(bob), null);
        mockMvc.perform(get("/api/v1/jobs").header("Authorization", bearer(bobs)))
                .andExpect(status().isUnauthorized());

        // The configured administrator's token keeps working.
        Authentication admin = signedIn("uid=admin,ou=people,dc=example,dc=gov");
        String admins = create("jobs:read", authentication(admin), null);
        mockMvc.perform(get("/api/v1/jobs").header("Authorization", bearer(admins)))
                .andExpect(status().isOk());
    }

    private static Authentication signedIn(String dn) {
        List<SimpleGrantedAuthority> roles = List.of(
                new SimpleGrantedAuthority(DirectoryPrincipalResolver.ROLE_USER),
                new SimpleGrantedAuthority(DirectoryPrincipalResolver.ROLE_ADMIN));
        DirectoryPrincipal principal = new DirectoryPrincipal(
                dn.substring(4, dn.indexOf(',')), null, null, dn, DirectoryPrincipal.AuthenticationMethod.LDAP, roles);
        return new UsernamePasswordAuthenticationToken(principal, null, roles);
    }

    @Test
    void theTokenIsNeverLoggedAuditedOrKeptInTheSession() throws Exception {
        long head = audit.headSeq();
        MockHttpSession session = new MockHttpSession();
        String token = create("jobs:run", admin(), session);
        mockMvc.perform(post("/api/v1/revocation/check").header("Authorization", bearer(token)))
                .andExpect(status().isAccepted());
        mockMvc.perform(get("/api/v1/certificates").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden());

        // Not in any log line, at any level.
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(line -> line != null && line.contains(token));
        // Not in the audit trail - the creation and the use are there, by prefix only.
        List<StoredEvent> recorded = audit.after(head, 1000);
        assertThat(recorded).extracting(StoredEvent::action).contains("api.token-created", "api.token-used", "api.request");
        assertThat(recorded).extracting(StoredEvent::line).noneMatch(line -> line.contains(token));
        // Not in the session the administrator created it from, in any attribute.
        for (String name : Collections.list(session.getAttributeNames())) {
            Object value = session.getAttribute(name);
            assertThat(String.valueOf(value)).doesNotContain(token);
            if (value instanceof java.io.Serializable) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                    out.writeObject(value);
                }
                assertThat(bytes.toString(java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(token);
            }
        }
        // And only its hash is stored.
        assertThat(jdbc.queryForList("SELECT token_hash FROM api_token", String.class)).doesNotContain(token);
    }

    @Test
    void writesAreAuditedAsTheTokenActingForItsCreator() throws Exception {
        String token = create("jobs:run");
        long head = audit.headSeq();
        mockMvc.perform(post("/api/v1/revocation/check").header("Authorization", bearer(token)))
                .andExpect(status().isAccepted());
        assertThat(audit.after(head, 1000)).filteredOn(event -> event.action().equals("api.request"))
                .singleElement()
                .satisfies(event -> assertThat(event.line())
                        .contains("\"type\":\"service\"", "api-token:", "API token of root", "/api/v1/revocation/check"));
    }

    private Long idOf(String secret) {
        return tokens.list().stream().filter(t -> t.getPrefix().equals(secret.substring(0, 12))).findFirst()
                .orElseThrow().getId();
    }

    private Set<String> actions(long head) {
        return audit.after(head, 1000).stream().map(StoredEvent::action).collect(Collectors.toSet());
    }
}

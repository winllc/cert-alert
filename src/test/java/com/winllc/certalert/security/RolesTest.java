package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.audit.AccessDenials;
import com.winllc.certalert.audit.AuditStore;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.support.EmbeddedDirectory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Roles beyond the administrator list: the read-only auditor, and roles from directory
 * groups ({@code memberOf}) - granted only by groups the configuration names.
 */
@SpringBootTest(properties = {
    "cert-alert.security.admin-identifiers=boss",
    // A DN has commas of its own, so it goes in by index.
    "cert-alert.security.auditor-identifiers[0]=" + RolesTest.AUDITOR_DN,
    "cert-alert.security.groups.admin[0]=" + RolesTest.ADMIN_GROUP,
    "cert-alert.security.groups.auditor[0]=" + RolesTest.AUDITOR_GROUP,
    // Wide open for an ordinary user - which an auditor still may not use.
    "cert-alert.security.contact-editors=AUTHENTICATED"
})
@ActiveProfiles("test")
class RolesTest {

    static final String AUDITOR_DN = "uid=audrey," + EmbeddedDirectory.PEOPLE_DN;
    static final String ADMIN_GROUP = "cn=CertAlert Admins,ou=groups," + EmbeddedDirectory.BASE_DN;
    static final String AUDITOR_GROUP = "cn=CertAlert Auditors,ou=groups," + EmbeddedDirectory.BASE_DN;

    private static EmbeddedDirectory directory;
    private static boolean synced;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private DirectoryGroups groups;

    @Autowired
    private AuditStore auditStore;

    @Autowired
    private AccessDenials denials;

    private MockMvc mockMvc;

    @BeforeAll
    static void startDirectory() {
        directory = new EmbeddedDirectory();
        directory.addUser("audrey", "Audrey Auditor", "audrey@example.gov");
        directory.addUserWithExtraAttributes("gail", "Gail Grouped", "gail@example.gov",
                Map.of("memberOf", List.of(ADMIN_GROUP)));
        directory.addUserWithExtraAttributes("ivan", "Ivan Inspector", "ivan@example.gov",
                Map.of("memberOf", List.of(AUDITOR_GROUP)));
        // In a group that sounds like administrators, and is not one the configuration names.
        directory.addUserWithExtraAttributes("olga", "Olga Other", "olga@example.gov",
                Map.of("memberOf", List.of("cn=Administrators,ou=groups," + EmbeddedDirectory.BASE_DN)));
        // Both: an administrator, who may change things.
        directory.addUserWithExtraAttributes("bea", "Bea Both", "bea@example.gov",
                Map.of("memberOf", List.of(ADMIN_GROUP, AUDITOR_GROUP)));
    }

    @AfterAll
    static void stopDirectory() {
        directory.close();
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
        if (!synced) {
            syncService.syncUsers();
            synced = true;
        }
        groups.forget();
    }

    private MockHttpSession signIn(String uid) throws Exception {
        MockHttpSession session = (MockHttpSession) mockMvc.perform(formLogin().user(uid).password("password"))
                .andReturn()
                .getRequest()
                .getSession(false);
        assertThat(session).isNotNull();
        return session;
    }

    private static List<String> rolesIn(MockHttpSession session) {
        SecurityContext security = (SecurityContext)
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        Authentication who = security.getAuthentication();
        return who.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .filter(role -> role.startsWith("ROLE_"))
                .toList();
    }

    private int as(MockHttpSession session, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.session(session).with(csrf())).andReturn().getResponse().getStatus();
    }

    @Test
    void anAuditorNamedByTheirDnReadsWhatAnAdministratorReads() throws Exception {
        MockHttpSession audrey = signIn("audrey");
        assertThat(rolesIn(audrey)).contains("ROLE_AUDITOR").doesNotContain("ROLE_ADMIN");

        assertThat(as(audrey, get("/admin"))).isEqualTo(200);
        assertThat(as(audrey, get("/admin/audit"))).isEqualTo(200);
        assertThat(as(audrey, get("/admin/license"))).isEqualTo(200);
        assertThat(as(audrey, get("/api/v1/audit/summary"))).isEqualTo(200);
        assertThat(as(audrey, get("/api/v1/admin/audit/events"))).isEqualTo(200);
        assertThat(as(audrey, get("/api/v1/admin/audit/delivery"))).isEqualTo(200);
        // The tables search by POST, and verifying the chain only reads it.
        assertThat(as(audrey, post("/api/v1/datatables/users").contentType(MediaType.APPLICATION_JSON)
                .content("{\"draw\":1,\"start\":0,\"length\":10,\"search\":{\"value\":\"\",\"regex\":false},"
                        + "\"order\":[],\"columns\":[{\"data\":\"uid\",\"name\":\"\",\"searchable\":true,"
                        + "\"orderable\":true,\"search\":{\"value\":\"\",\"regex\":false}}]}")))
                .isEqualTo(200);
        assertThat(as(audrey, post("/api/v1/admin/audit/verify"))).isEqualTo(200);
        // The page says what they are.
        mockMvc.perform(get("/users").session(audrey))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Auditor")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("cert-alert-read-only")));
    }

    @Test
    void anAuditorChangesNothing() throws Exception {
        MockHttpSession audrey = signIn("audrey");
        long head = auditStore.headSeq();

        List<MockHttpServletRequestBuilder> writes = List.of(
                post("/api/v1/sync"),
                post("/api/v1/sync/refresh"),
                post("/api/v1/revocation/check"),
                post("/api/v1/notifications/digest"),
                post("/api/v1/notifications/read-all"),
                put("/api/v1/notifications/settings").contentType(MediaType.APPLICATION_JSON).content("{\"leadDays\":10}"),
                post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"),
                post("/api/v1/servers/1/contacts").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b.gov\"}"),
                delete("/api/v1/servers/1/contacts/1"),
                post("/api/v1/servers/1/probe"),
                post("/api/v1/users/1/addresses").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b.gov\"}"),
                post("/api/v1/admin/server-attributes").contentType(MediaType.APPLICATION_JSON).content("{}"),
                post("/api/v1/admin/audit/tokens").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"),
                post("/admin/license").param("text", "x"));
        for (MockHttpServletRequestBuilder write : writes) {
            var request = write.buildRequest(context.getServletContext());
            assertThat(as(audrey, write)).as(request.getMethod() + " " + request.getRequestURI()).isEqualTo(403);
        }
        denials.flush();
        assertThat(auditStore.after(head, 1000)).anySatisfy(event -> assertThat(event.action()).isEqualTo("access.denied"));
    }

    @Test
    void anAdministratorsGroupMakesAnAdministrator() throws Exception {
        MockHttpSession gail = signIn("gail");
        assertThat(rolesIn(gail)).contains("ROLE_ADMIN");
        assertThat(as(gail, get("/admin"))).isEqualTo(200);
    }

    @Test
    void anAuditorsGroupMakesAnAuditor() throws Exception {
        MockHttpSession ivan = signIn("ivan");
        assertThat(rolesIn(ivan)).contains("ROLE_AUDITOR").doesNotContain("ROLE_ADMIN");
        assertThat(as(ivan, post("/api/v1/sync/refresh"))).isEqualTo(403);
    }

    /** A group the configuration does not name grants nothing, whatever it is called. */
    @Test
    void aGroupNobodyNamedGrantsNothing() throws Exception {
        MockHttpSession olga = signIn("olga");
        assertThat(rolesIn(olga)).containsExactly("ROLE_USER");
        assertThat(as(olga, get("/admin"))).isEqualTo(403);
    }

    /**
     * A reader - signed in, in the directory, in no role - reads the inventory and their own
     * notifications, and reaches nothing an administrator or auditor does: no administration
     * page or API, no job, no change to what everybody else is written to about. Each refusal
     * is a 403, recorded, and changes nothing.
     */
    @Test
    void aReaderReadsTheirOwnAndReachesNoAdministration() throws Exception {
        MockHttpSession olga = signIn("olga");
        assertThat(rolesIn(olga)).containsExactly("ROLE_USER");
        long head = auditStore.headSeq();

        // What any reader may read.
        for (String page : List.of("/users", "/servers", "/certificates", "/notifications",
                "/api/v1/notifications", "/api/v1/notifications/settings", "/api/v1/license")) {
            assertThat(as(olga, get(page))).as("GET " + page).isEqualTo(200);
        }
        mockMvc.perform(get("/api/v1/notifications/settings").session(olga))
                .andExpect(jsonPath("$.editable")
                        .value(false));
        mockMvc.perform(get("/api/v1/license").session(olga))
                .andExpect(jsonPath("$.installationId")
                        .doesNotExist());

        // Administration, read or written, is not theirs.
        List<MockHttpServletRequestBuilder> refused = List.of(
                get("/admin"),
                get("/admin/audit"),
                get("/admin/license"),
                get("/admin/integrations"),
                get("/api/v1/audit/summary"),
                get("/api/v1/admin/audit/events"),
                get("/api/v1/admin/audit/delivery"),
                get("/api/v1/admin/audit/tokens"),
                get("/api/v1/admin/webhooks"),
                get("/api/v1/admin/api-tokens"),
                get("/api/v1/jobs"),
                post("/api/v1/admin/audit/verify"),
                post("/api/v1/sync"),
                post("/api/v1/sync/users"),
                post("/api/v1/sync/refresh"),
                post("/api/v1/revocation/check"),
                post("/api/v1/notifications/digest"),
                post("/api/v1/notifications/digest").param("dryRun", "true"),
                put("/api/v1/notifications/settings").contentType(MediaType.APPLICATION_JSON).content("{\"leadDays\":3}"),
                post("/api/v1/admin/webhooks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"url\":\"https://hooks.example.gov/x\"}"),
                post("/api/v1/admin/api-tokens").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"),
                post("/api/v1/admin/audit/tokens").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"),
                post("/api/v1/admin/server-attributes").contentType(MediaType.APPLICATION_JSON).content("{}"),
                post("/api/v1/projects/1/admins/1"),
                delete("/api/v1/projects/1/admins/1"),
                post("/admin/license").param("text", "x"));
        for (MockHttpServletRequestBuilder request : refused) {
            var built = request.buildRequest(context.getServletContext());
            assertThat(as(olga, request)).as(built.getMethod() + " " + built.getRequestURI()).isEqualTo(403);
        }

        // The refused change did not happen, and the refusals are on the record.
        mockMvc.perform(get("/api/v1/notifications/settings").session(olga))
                .andExpect(jsonPath("$.leadDays")
                        .value(30));
        denials.flush();
        assertThat(auditStore.after(head, 1000))
                .filteredOn(event -> event.action().equals("access.denied"))
                .isNotEmpty();
    }

    @Test
    void somebodyWhoIsBothIsAnAdministrator() throws Exception {
        MockHttpSession bea = signIn("bea");
        assertThat(rolesIn(bea)).contains("ROLE_ADMIN", "ROLE_AUDITOR");
        assertThat(as(bea, post("/api/v1/sync/refresh"))).isEqualTo(202);
    }

    @Test
    void theNamedAdministratorIsStillOne() throws Exception {
        directory.addUser("boss", "Boss Person", "boss@example.gov");
        MockHttpSession boss = signIn("boss");
        assertThat(rolesIn(boss)).contains("ROLE_ADMIN").doesNotContain("ROLE_AUDITOR");
    }
}

package com.winllc.certalert.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.unboundid.ldap.sdk.Entry;
import com.winllc.certalert.security.SecurityConfig;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestCertificates;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A demo: sign in as anybody, change nothing.
 *
 * <p>The two halves are tested separately on purpose. That signing in as one account shows
 * something another does not is the point of the thing, and that none of them can change
 * anything is what makes it safe to leave running; a change that quietly broke either
 * would leave the other looking fine.
 *
 * <p>The administrator's cases matter most. They are the account with the most to see, so
 * they are the one whose write being refused is easiest to get wrong - a rule written as
 * "administrators may" would pass every other test here.
 */
@SpringBootTest(
        properties = {
            "cert-alert.demo.enabled=true",
            // This class brings its own directory, on its own port. A demo would otherwise
            // generate one and try to serve it on the same address.
            "cert-alert.demo.generate-directory=false"
        })
@ActiveProfiles("test")
class DemoModeTest {

    /** The password every entry in the embedded directory carries. */
    private static final String PASSWORD = "password";

    /** Named in cert-alert.security.admin-identifiers for the test profile. */
    private static final String ADMIN = "admin";

    /** Signed in, and nothing more. */
    private static final String READER = "dana";

    private static EmbeddedDirectory directory;
    private static List<Entry> seeded;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ApplicationContext beans;

    @Autowired
    private DirectorySyncService syncService;

    private MockMvc mockMvc;
    private MockHttpSession admin;
    private MockHttpSession reader;

    @BeforeAll
    static void startDirectory() {
        directory = new EmbeddedDirectory();
        Instant now = Instant.now();
        byte[] certificate = TestCertificates.der(
                "dana@example.gov", now.minus(Duration.ofDays(30)), now.plus(Duration.ofDays(20)));
        directory.addUser(READER, "Dana Day", "dana@example.gov", certificate);
        directory.addUser(ADMIN, "Ada Admin", "admin@example.gov");
        // The demo's own accounts, loaded from the seed exactly as a generated directory
        // would carry them - this class supplies its own directory, so it seeds it itself.
        seeded = DemoAccountSeed.read(EmbeddedDirectory.BASE_DN, PASSWORD);
        seeded.forEach(directory::add);
        directory.addServer("web09", "https://web09.example.gov", new String[] {"dana@example.gov"}, certificate);
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
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        syncService.syncUsers();
        admin = signIn(ADMIN);
        reader = signIn(READER);
    }

    // --- one policy or the other, never both -------------------------------------------

    @Test
    void theOrdinarySecurityConfigurationIsNotInForce() {
        assertThat(beans.getBeanNamesForType(SecurityConfig.class)).isEmpty();
        assertThat(beans.getBeanNamesForType(DemoSecurityConfig.class)).hasSize(1);
    }

    // --- the sign-in page, and its accounts ----------------------------------------------

    /**
     * The trap this is here for: an anonymous token reports itself authenticated, so a
     * rule written as "is this authenticated" let a demo serve every page to somebody who
     * had never been near the form.
     */
    @Test
    void nothingIsServedBeforeSigningIn() throws Exception {
        for (String page : new String[] {"/users", "/servers", "/projects", "/metrics", "/notifications", "/admin"}) {
            mockMvc.perform(get(page))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login"));
        }
        mockMvc.perform(get("/login")).andExpect(status().isOk());
    }

    /**
     * The accounts, and the password that opens them, are on the page a visitor lands on -
     * and they come from the directory, not from a list in the code. This is the whole
     * path: the seed goes into a directory, and the sign-in page reads it back out.
     */
    @Test
    void theSignInPageListsTheAccountsTheDirectoryHolds() throws Exception {
        String page = mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(seeded).isNotEmpty();
        for (Entry account : seeded) {
            assertThat(page).contains(account.getAttributeValue("uid"));
            assertThat(page).contains(account.getAttributeValue(DemoAccounts.ROLE));
            assertThat(page).contains(account.getAttributeValue("displayName"));
        }
        assertThat(page).contains(PASSWORD);
    }

    /** A password is checked by binding to the directory, exactly as anywhere else. */
    @Test
    void aDirectoryPasswordSignsYouIn() throws Exception {
        mockMvc.perform(formLogin().user(READER).password(PASSWORD)).andExpect(authenticated());
        mockMvc.perform(formLogin().user(READER).password("wrong")).andExpect(unauthenticated());
    }

    // --- what each role may read ---------------------------------------------------------

    @Test
    void theAdministrationPagesAreTheAdministratorsAlone() throws Exception {
        mockMvc.perform(get("/admin").session(admin)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/audit/summary").session(admin)).andExpect(status().isOk());

        mockMvc.perform(get("/admin").session(reader)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/audit/summary").session(reader)).andExpect(status().isForbidden());
    }

    /**
     * Refused a page, a browser is sent to the error page rather than handed problem
     * detail to render in the window.
     *
     * <p>Asserted as the forward rather than as the rendered page, because MockMvc records
     * a forward and does not follow it.
     */
    @Test
    void aRefusedPageIsAPageAndNotProblemDetail() throws Exception {
        mockMvc.perform(get("/admin").session(reader).accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden())
                .andExpect(forwardedUrl("/error"));
    }

    /** The same refusal to a script is problem detail, not a page. */
    @Test
    void aRefusedApiReadIsProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/audit/summary").session(reader))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void everyOtherPageIsOpenToAnybodySignedIn() throws Exception {
        for (String page : new String[] {"/users", "/servers", "/projects", "/metrics", "/notifications"}) {
            mockMvc.perform(get(page).session(reader)).andExpect(status().isOk());
        }
    }

    /**
     * The tables ask by POST, because that is how DataTables sends paging and filters.
     * They are named one by one rather than let through as "POSTs that look harmless".
     */
    @Test
    void theSearchTablesAnswerAnybodySignedIn() throws Exception {
        for (String table : new String[] {"users", "servers"}) {
            mockMvc.perform(post("/api/v1/datatables/" + table)
                            .session(reader)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(table("id")))
                    .andExpect(status().isOk());
        }
        // The audit table is the whole trail, so it follows the administration page.
        mockMvc.perform(post("/api/v1/datatables/audit")
                        .session(admin)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(table("occurredAt")))
                .andExpect(status().isOk());
    }

    // --- nothing changeable, for anybody --------------------------------------------------

    /**
     * By method rather than by path, so an endpoint added tomorrow is refused without
     * anybody remembering to add it here - and for the administrator too, who is the
     * account a rule about roles would have let through.
     */
    @Test
    void everyUnsafeMethodIsRefusedEvenForTheAdministrator() throws Exception {
        for (MockHttpSession session : new MockHttpSession[] {admin, reader}) {
            mockMvc.perform(post("/api/v1/sync").session(session).with(csrf()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/sync/prune").session(session).with(csrf()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/revocation/check").session(session).with(csrf()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/notifications/digest").session(session).with(csrf()))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/v1/projects")
                            .session(session)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Anything\"}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(put("/api/v1/notifications/settings")
                            .session(session)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"leadTimeDays\":365}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(delete("/api/v1/projects/1").session(session).with(csrf()))
                    .andExpect(status().isForbidden());
        }
    }

    /**
     * The one POST that is a read: it builds every message, says what would have gone out,
     * and saves nothing. Refusing it left a demo unable to show the round-up at all, since
     * the real one is - rightly - refused like every other write.
     */
    @Test
    void theAdministratorMayRehearseTheRoundUp() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/digest")
                        .param("dryRun", "true")
                        .session(admin)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true));
    }

    /**
     * And only when it is asked to rehearse. Whether this endpoint writes depends on how it
     * is called, so the demo has to read the parameter rather than the path - and anything
     * that is not an explicit true is the real round-up.
     */
    @Test
    void theRealRoundUpIsStillRefused() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/digest").session(admin).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/notifications/digest")
                        .param("dryRun", "false")
                        .session(admin)
                        .with(csrf()))
                .andExpect(status().isForbidden());
        // Not the name the controller binds, so the controller would call this a real run.
        mockMvc.perform(post("/api/v1/notifications/digest")
                        .param("dryrun", "true")
                        .session(admin)
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    /** Who would be written to is an administrator's business, as it is anywhere else. */
    @Test
    void aReaderMayNotRehearseTheRoundUp() throws Exception {
        mockMvc.perform(post("/api/v1/notifications/digest")
                        .param("dryRun", "true")
                        .session(reader)
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aRefusedWriteSaysWhyRatherThanLookingBroken() throws Exception {
        mockMvc.perform(post("/api/v1/sync").session(admin).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Read-only demo"))
                .andExpect(jsonPath("$.detail").value(DemoSecurityConfig.REFUSAL))
                .andExpect(jsonPath("$.instance").value("/api/v1/sync"));
    }

    // --- helpers ---------------------------------------------------------------------------

    /**
     * Signs in for real and keeps the session.
     *
     * <p>Through the form and the directory bind rather than by handing MockMvc a token,
     * because the roles are the thing under test: a fabricated authentication would carry
     * whatever authorities the test felt like giving it, and would pass whether or not the
     * application resolves an administrator correctly.
     */
    private MockHttpSession signIn(String username) throws Exception {
        return (MockHttpSession) mockMvc.perform(formLogin().user(username).password(PASSWORD))
                .andExpect(authenticated())
                .andReturn()
                .getRequest()
                .getSession(false);
    }

    private static String table(String column) {
        return """
                {"draw":1,"start":0,"length":10,
                 "columns":[{"data":"%s","name":"","searchable":true,"orderable":true,
                             "search":{"value":"","regex":false}}],
                 "order":[{"column":0,"dir":"asc"}],
                 "search":{"value":"","regex":false}}"""
                .formatted(column);
    }
}

package com.winllc.certalert.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.security.DirectoryPrincipalResolver;
import com.winllc.certalert.security.SecurityConfig;
import com.winllc.certalert.support.EmbeddedDirectory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A demo: no sign-in, every page readable, nothing changeable.
 *
 * <p>The two halves are tested separately on purpose. That a visitor can reach the
 * administration pages is the point of the thing, and that they cannot change anything is
 * what makes it safe to leave running; a change that quietly broke either would leave the
 * other looking fine.
 */
@SpringBootTest(properties = {"cert-alert.demo.enabled=true", "cert-alert.demo.signed-in-as=demo@example.gov"})
@ActiveProfiles("test")
class DemoModeTest {

    private static EmbeddedDirectory directory;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ApplicationContext beans;

    private MockMvc mockMvc;

    @BeforeAll
    static void startDirectory() {
        directory = new EmbeddedDirectory();
        Instant now = Instant.now();
        byte[] certificate = com.winllc.certalert.support.TestCertificates.der(
                "dana@example.gov", now.minus(Duration.ofDays(30)), now.plus(Duration.ofDays(20)));
        directory.addUser("dana", "Dana Day", "dana@example.gov", certificate);
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
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // --- one policy or the other, never both -------------------------------------------

    @Test
    void theOrdinarySecurityConfigurationIsNotInForce() {
        assertThat(beans.getBeanNamesForType(SecurityConfig.class)).isEmpty();
        assertThat(beans.getBeanNamesForType(DemoSecurityConfig.class)).hasSize(1);
    }

    // --- everything readable, without signing in ---------------------------------------

    @Test
    void everyPageIsServedToSomebodyWhoNeverSignedIn() throws Exception {
        for (String page : new String[] {"/users", "/servers", "/projects", "/metrics", "/notifications"}) {
            mockMvc.perform(get(page)).andExpect(status().isOk());
        }
        // The front page is the application's own redirect onto the people table. Named
        // here because the failure worth catching is it pointing at a sign-in page.
        mockMvc.perform(get("/")).andExpect(redirectedUrl("/users"));
    }

    /** The point of a demo of this application: the administration pages are part of it. */
    @Test
    void theAdministrationPagesAreOpenToo() throws Exception {
        mockMvc.perform(get("/admin")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/audit/summary")).andExpect(status().isOk());
    }

    /**
     * The tables ask by POST, because that is how DataTables sends paging and filters.
     * They are named one by one rather than let through as "POSTs that look harmless".
     */
    @Test
    void theSearchTablesStillAnswer() throws Exception {
        for (String table : new String[] {"users", "servers", "audit"}) {
            mockMvc.perform(post("/api/v1/datatables/" + table)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    """
                                    {"draw":1,"start":0,"length":10,
                                     "columns":[{"data":"id","name":"","searchable":true,"orderable":true,
                                                 "search":{"value":"","regex":false}}],
                                     "order":[{"column":0,"dir":"asc"}],
                                     "search":{"value":"","regex":false}}"""))
                    .andExpect(status().isOk());
        }
    }

    /**
     * Whoever the controllers see has to be a real authentication: a servlet reports no
     * principal for an anonymous one, so an {@code Authentication} parameter arrives null
     * and every administrator check quietly answers no.
     */
    @Test
    void theVisitorIsAnAuthenticatedAdministrator() throws Exception {
        // Asked of the request as it leaves the security chain, because that is where the
        // answer differs: an anonymous authentication sits in the context perfectly well
        // and still reports no principal here.
        AtomicReference<Principal> seen = new AtomicReference<>();
        MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .addFilter((ServletRequest request, ServletResponse response, FilterChain chain) -> {
                    seen.set(((HttpServletRequest) request).getUserPrincipal());
                    chain.doFilter(request, response);
                })
                .build()
                .perform(get("/admin"))
                .andExpect(status().isOk());

        assertThat(seen.get()).isInstanceOf(Authentication.class);
        Authentication authentication = (Authentication) seen.get();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority))
                .contains(DirectoryPrincipalResolver.ROLE_USER, DirectoryPrincipalResolver.ROLE_ADMIN);
        assertThat(authentication.getPrincipal()).isInstanceOf(DirectoryPrincipal.class);
        assertThat(((DirectoryPrincipal) authentication.getPrincipal()).getUsername()).isEqualTo("demo@example.gov");
    }

    // --- nothing changeable -------------------------------------------------------------

    /**
     * By method rather than by path, so an endpoint added tomorrow is refused without
     * anybody remembering to add it here.
     */
    @Test
    void everyUnsafeMethodIsRefused() throws Exception {
        mockMvc.perform(post("/api/v1/sync")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/sync/prune")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/revocation/check")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/changelog/poll")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/notifications/digest")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/notifications/digest").param("dryRun", "true"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/notifications/read-all")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Anything\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/notifications/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leadTimeDays\":365}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/servers/1/attributes/ATOStatus")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":[\"x\"]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/projects/1")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/servers/1/contacts/1")).andExpect(status().isForbidden());
    }

    /** No CSRF token is sent by any of these, so the refusal has to be the rule's, not CSRF's. */
    @Test
    void aRefusalSaysWhyRatherThanLookingBroken() throws Exception {
        mockMvc.perform(post("/api/v1/sync"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Read-only demo"))
                .andExpect(jsonPath("$.detail").value(DemoSecurityConfig.REFUSAL))
                .andExpect(jsonPath("$.instance").value("/api/v1/sync"));
    }

    /** There is nothing to sign in to, and nothing to sign out of. */
    @Test
    void thereIsNoSignInOrSignOut() throws Exception {
        mockMvc.perform(post("/login")).andExpect(status().isForbidden());
        mockMvc.perform(post("/logout")).andExpect(status().isForbidden());
    }
}

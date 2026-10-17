package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.audit.AuditStore;
import com.winllc.certalert.audit.StoredEvent;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestCertificates;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The DoD notice-and-consent banner, enforced on the server: no sign-in of any kind - a
 * password, a client certificate - succeeds until the session has agreed.
 */
@SpringBootTest(properties = "ENS_CONSENT_BANNER_ENABLED=true")
@ActiveProfiles("test")
class ConsentBannerTest {

    private static EmbeddedDirectory directory;
    private static X509Certificate carolCertificate;
    private static boolean synced;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private AuditStore auditStore;

    private MockMvc mockMvc;

    @BeforeAll
    static void startDirectory() {
        directory = new EmbeddedDirectory();
        Instant now = Instant.now();
        byte[] carol = TestCertificates.der("carol@example.gov", now.minus(Duration.ofDays(30)),
                now.plus(Duration.ofDays(365)));
        carolCertificate = TestCertificates.certificateOf(carol);
        directory.addUser("carol", "Carol Consent", "carol@example.gov", carol);
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
        // Once per class. Carol is this class's own: the in-memory database is shared by every
        // test class, and a name another one also uses could hold its certificate instead.
        if (!synced) {
            syncService.syncUsers();
            synced = true;
        }
    }

    @Test
    void everyPageWaitsForTheBannerTheSignInPageIncluded() throws Exception {
        mockMvc.perform(get("/users")).andExpect(redirectedUrl("/consent"));
        mockMvc.perform(get("/login")).andExpect(redirectedUrl("/consent"));
        // An API call is not a browser to redirect.
        mockMvc.perform(post("/api/v1/datatables/users").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void theBannerSaysTheStandardTextAndAsksForAgreement() throws Exception {
        mockMvc.perform(get("/consent"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString(
                        "U.S. Government (USG) Information System (IS) Notice and Consent")))
                .andExpect(content().string(Matchers.containsString(
                        "The USG routinely intercepts and monitors communications on this IS")))
                .andExpect(content().string(Matchers.containsString("See User Agreement for details.")))
                .andExpect(content().string(Matchers.containsString("I agree")));
    }

    @Test
    void aClientCertificateDoesNotSignInBeforeAgreement() throws Exception {
        mockMvc.perform(get("/users").with(x509(carolCertificate)))
                .andExpect(redirectedUrl("/consent"))
                .andExpect(unauthenticated());
    }

    @Test
    void norDoesAPassword() throws Exception {
        mockMvc.perform(formLogin().user("carol").password("password"))
                .andExpect(redirectedUrl("/consent"))
                .andExpect(unauthenticated());
    }

    @Test
    void agreeingIsAuditedAndLetsTheSessionSignIn() throws Exception {
        long head = auditStore.headSeq();
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/consent").session(session).with(csrf()).with(request -> {
                    request.setRemoteAddr("10.9.8.7");
                    return request;
                }))
                .andExpect(redirectedUrl("/"));

        List<StoredEvent> acknowledged = auditStore.after(head, 100).stream()
                .filter(event -> "auth.consent-acknowledged".equals(event.action()))
                .toList();
        assertThat(acknowledged).singleElement().satisfies(event -> assertThat(event.line())
                .contains("\"category\":\"authentication\"", "\"outcome\":\"success\"", "\"ip\":\"10.9.8.7\""));

        mockMvc.perform(get("/users").session(session).with(x509(carolCertificate)))
                .andExpect(status().isOk())
                .andExpect(authenticated().withUsername("carol@example.gov"));
    }

    @Test
    void agreeingNeedsTheFormsToken() throws Exception {
        mockMvc.perform(post("/consent")).andExpect(status().isForbidden());
    }

    @Test
    void theMachineEndpointsAreNotBrowserSessions() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/css/app.css")).andExpect(status().isOk());
    }

    @Test
    void theTextIsParagraphsAndAList() {
        List<ConsentBanner.Block> blocks = ConsentBanner.parse(ConsentBanner.DEFAULT_TEXT);

        assertThat(blocks).hasSize(2);
        assertThat(blocks.getFirst().paragraph()).startsWith("You are accessing a U.S. Government (USG)");
        assertThat(blocks.get(1).items()).hasSize(5);
        assertThat(blocks.get(1).items().get(1)).isEqualTo("At any time, the USG may inspect and seize data stored on this IS.");

        // An operator's own: wrapped list items, and markup that stays text.
        List<ConsentBanner.Block> own = ConsentBanner.parse("First <b>line</b>\nsame paragraph.\n\n- one\n  wrapped\n- two\n");
        assertThat(own.getFirst().paragraph()).isEqualTo("First <b>line</b> same paragraph.");
        assertThat(own.get(1).items()).containsExactly("one wrapped", "two");
    }

    @Test
    void aFileWinsOverTheText() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("consent", ".txt");
        java.nio.file.Files.writeString(file, "From the file.");
        EnsSecurityProperties properties = new EnsSecurityProperties();
        properties.getConsentBanner().setText("From the setting.");
        properties.getConsentBanner().setFile(file.toString());
        properties.getConsentBanner().setTitle("Our title");

        ConsentBanner banner = new ConsentBanner(properties);

        assertThat(banner.getTitle()).isEqualTo("Our title");
        assertThat(banner.getBlocks()).singleElement()
                .satisfies(block -> assertThat(block.paragraph()).isEqualTo("From the file."));

        properties.getConsentBanner().setFile(file.resolveSibling("missing-" + file.getFileName()).toString());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ConsentBanner(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ENS_CONSENT_BANNER_FILE");
    }
}

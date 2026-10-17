package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.audit.AuditStore;
import com.winllc.certalert.audit.StoredEvent;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestCertificates;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The ENS session controls: idle (shorter for administrators), absolute, and how many
 * sessions one person may hold - each ending in the sign-in page and an
 * {@code auth.session-expired} record saying why.
 */
@SpringBootTest(properties = {
    "ENS_SESSION_IDLE_TIMEOUT=20m",
    "ENS_SESSION_ADMIN_IDLE_TIMEOUT=10m",
    "cert-alert.security.admin-identifiers=erin@example.gov"
})
@ActiveProfiles("test")
class SessionControlsTest {

    private static EmbeddedDirectory directory;
    private static X509Certificate daveCertificate;
    private static X509Certificate erinCertificate;
    private static boolean synced;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private EnsSecurityProperties properties;

    @Autowired
    private SessionPolicy policy;

    @Autowired
    private AuditStore auditStore;

    private MockMvc mockMvc;

    @BeforeAll
    static void startDirectory() {
        directory = new EmbeddedDirectory();
        Instant now = Instant.now();
        byte[] dave = TestCertificates.der("dave@example.gov", now.minus(Duration.ofDays(30)),
                now.plus(Duration.ofDays(365)));
        byte[] erin = TestCertificates.der("erin@example.gov", now.minus(Duration.ofDays(30)),
                now.plus(Duration.ofDays(365)));
        daveCertificate = TestCertificates.certificateOf(dave);
        erinCertificate = TestCertificates.certificateOf(erin);
        directory.addUser("dave", "Dave Sessions", "dave@example.gov", dave);
        directory.addUser("erin", "Erin Admin", "erin@example.gov", erin);
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
    }

    @AfterEach
    void restore() {
        properties.getSession().setMaxPerUser(0);
        properties.getSession().setAbsoluteTimeout(Duration.ofHours(12));
    }

    @Test
    void theSettingsAreReadFromTheirEnsNames() {
        assertThat(properties.getSession().getIdleTimeout()).isEqualTo(Duration.ofMinutes(20));
        assertThat(properties.getSession().effectiveAdminIdleTimeout()).isEqualTo(Duration.ofMinutes(10));
        assertThat(properties.getSession().getAbsoluteTimeout()).isEqualTo(Duration.ofHours(12));
        assertThat(properties.getSession().getMaxPerUser()).isZero();
    }

    @Test
    void signingInSetsTheIdleTimeoutAndANewSessionId() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String before = session.getId();

        mockMvc.perform(get("/users").session(session).with(x509(daveCertificate))).andExpect(status().isOk());

        assertThat(session.getMaxInactiveInterval()).isEqualTo(20 * 60);
        assertThat(session.getAttribute(SessionPolicy.SIGNED_IN_AT)).isInstanceOf(Instant.class);
        assertThat(session.getId()).as("not the id the browser had before signing in").isNotEqualTo(before);
    }

    @Test
    void aCertificateIsCheckedOnEveryRequestButSignsInOncePerSession() throws Exception {
        long head = auditStore.headSeq();
        MockHttpSession session = new MockHttpSession();

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/users").session(session).with(x509(daveCertificate))).andExpect(status().isOk());
        }

        assertThat(auditStore.after(head, 1000).stream().filter(event -> "auth.login".equals(event.action())))
                .hasSize(1);
    }

    @Test
    void anAdministratorGetsTheShorterIdleTimeout() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(get("/users").session(session).with(x509(erinCertificate))).andExpect(status().isOk());

        assertThat(session.getMaxInactiveInterval()).isEqualTo(10 * 60);
    }

    @Test
    void aSessionEndsAtItsAbsoluteTimeoutHoweverBusy() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/users").session(session).with(x509(daveCertificate))).andExpect(status().isOk());
        long head = auditStore.headSeq();

        session.setAttribute(SessionPolicy.SIGNED_IN_AT, Instant.now().minus(Duration.ofHours(13)));

        // A certificate is presented on every request, and is checked on every one.
        mockMvc.perform(get("/users").session(session).with(x509(daveCertificate)))
                .andExpect(redirectedUrl("/login?expired"));
        assertThat(session.isInvalid()).isTrue();
        assertThat(expiries(head)).singleElement().satisfies(line -> assertThat(line)
                .contains("\"reason\":\"absolute\"", "\"id\":\"dave@example.gov\""));
    }

    @Test
    void anApiCallInAnEndedSessionGetsA401() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(get("/users").session(session).with(x509(daveCertificate))).andExpect(status().isOk());
        session.setAttribute(SessionPolicy.SIGNED_IN_AT, Instant.now().minus(Duration.ofHours(13)));

        mockMvc.perform(post("/api/v1/datatables/users").session(session).with(csrf()).with(x509(daveCertificate)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void overTheLimitTheOldestSessionEnds() throws Exception {
        properties.getSession().setMaxPerUser(2);
        long head = auditStore.headSeq();
        MockHttpSession first = new MockHttpSession();
        MockHttpSession second = new MockHttpSession();
        MockHttpSession third = new MockHttpSession();

        mockMvc.perform(get("/users").session(first).with(x509(daveCertificate))).andExpect(status().isOk());
        Thread.sleep(5);
        mockMvc.perform(get("/users").session(second).with(x509(daveCertificate))).andExpect(status().isOk());
        // The first is the one most recently used, and still the one that goes: oldest sign-in.
        mockMvc.perform(get("/users").session(first).with(x509(daveCertificate))).andExpect(status().isOk());
        Thread.sleep(5);
        mockMvc.perform(get("/users").session(third).with(x509(daveCertificate))).andExpect(status().isOk());

        mockMvc.perform(get("/users").session(first).with(x509(daveCertificate)))
                .andExpect(redirectedUrl("/login?expired"));
        mockMvc.perform(get("/users").session(second).with(x509(daveCertificate))).andExpect(status().isOk())
                .andExpect(authenticated().withUsername("dave@example.gov"));
        mockMvc.perform(get("/users").session(third).with(x509(daveCertificate))).andExpect(status().isOk());
        // Recorded against the session it ended. (Dave's sessions from the other cases here are
        // still held, since nothing destroys a mock session, and may go first: what matters is
        // that the first of these three went, and the two after it did not.)
        String firstSession = com.winllc.certalert.audit.AuditFormat.sha256Hex(first.getId()).substring(0, 16);
        assertThat(expiries(head))
                .filteredOn(line -> line.contains("\"session\":\"" + firstSession + "\""))
                .singleElement()
                .satisfies(line -> assertThat(line).contains("\"reason\":\"concurrent\"", "\"id\":\"dave@example.gov\""));
    }

    @Test
    void aSessionLeftUnusedIsRecordedAsIdleWhenTheContainerEndsIt() {
        long head = auditStore.headSeq();
        Authentication dave = signedInAs(daveCertificate);

        policy.sessionDestroyed(new HttpSessionEvent(destroyedSession("idle-1", dave, Duration.ofMinutes(21))));
        // Used a moment ago: a sign-out or a shutdown, not an expiry.
        policy.sessionDestroyed(new HttpSessionEvent(destroyedSession("busy-1", dave, Duration.ofSeconds(30))));

        assertThat(expiries(head)).singleElement().satisfies(line -> assertThat(line)
                .contains("\"reason\":\"idle\"", "\"id\":\"dave@example.gov\""));
    }

    private Authentication signedInAs(X509Certificate certificate) {
        MockHttpSession session = new MockHttpSession();
        try {
            MvcResult result = mockMvc.perform(get("/users").session(session).with(x509(certificate))).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return (Authentication) session.getAttribute(SessionPolicy.SIGNED_IN_AS);
    }

    private static HttpSession destroyedSession(String id, Authentication who, Duration unusedFor) {
        HttpSession session = mock(HttpSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getMaxInactiveInterval()).thenReturn(20 * 60);
        when(session.getLastAccessedTime()).thenReturn(System.currentTimeMillis() - unusedFor.toMillis());
        when(session.getAttribute(SessionPolicy.SIGNED_IN_AT)).thenReturn(Instant.now().minus(Duration.ofHours(1)));
        when(session.getAttribute(SessionPolicy.SIGNED_IN_AS)).thenReturn(who);
        return session;
    }

    private List<String> expiries(long head) {
        return auditStore.after(head, 1000).stream()
                .filter(event -> "auth.session-expired".equals(event.action()))
                .map(StoredEvent::line)
                .toList();
    }
}

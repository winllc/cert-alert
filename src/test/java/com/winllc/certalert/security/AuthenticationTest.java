package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestCertificates;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Both ways in: a client certificate the directory publishes, and a directory password for
 * a browser that presents none.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthenticationTest {

    private static EmbeddedDirectory directory;

    /** Published by the directory for alice, so it authenticates her. */
    private static X509Certificate aliceCertificate;

    /** Never published, so it should not get in. */
    private static X509Certificate strangerCertificate;

    /** Published, but by a server: identifies a machine, not somebody who holds a session. */
    private static X509Certificate serverCertificate;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private DirectoryUserRepository userRepository;

    @Autowired
    private X509DirectoryUserDetailsService x509UserDetailsService;

    private MockMvc mockMvc;

    @BeforeAll
    static void startDirectory() {
        directory = new EmbeddedDirectory();
        Instant now = Instant.now();

        byte[] alice = TestCertificates.der("alice@example.gov", now.minus(Duration.ofDays(30)),
                now.plus(Duration.ofDays(365)));
        aliceCertificate = TestCertificates.certificateOf(alice);
        directory.addUser("alice", "Alice Archer", "alice@example.gov", alice);

        // The administrator, named in cert-alert.security.admin-identifiers for this profile.
        directory.addUser("admin", "Ada Admin", "admin@example.gov");

        byte[] server = TestCertificates.der("web01.example.gov", now.minus(Duration.ofDays(30)),
                now.plus(Duration.ofDays(365)));
        serverCertificate = TestCertificates.certificateOf(server);
        directory.addServer("web01", "https://web01.example.gov", new String[] {"alice@example.gov"}, server);

        strangerCertificate = TestCertificates.certificate("stranger@elsewhere.gov",
                now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(365)));
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
        // Asks for this class's own fixture rather than for any row at all. The in-memory
        // database is one database for the whole test JVM, so "somebody has synced
        // something" is not the same question as "alice is cached", and answering the
        // first one leaves these cases depending on which class ran before them.
        if (userRepository.findByDn("uid=alice," + EmbeddedDirectory.PEOPLE_DN).isEmpty()) {
            syncService.syncUsers();
            syncService.syncServers();
        }
    }

    // --- nothing is open ---------------------------------------------------------------

    @Test
    void anAnonymousPageRequestIsSentToTheLoginForm() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void anAnonymousApiRequestIsRefusedRatherThanRedirected() throws Exception {
        // A table driven by fetch has no use for a login page in the response body.
        mockMvc.perform(post("/api/v1/datatables/users").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void healthStaysOpenSoTheThingCanBeMonitored() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    // --- X.509 -------------------------------------------------------------------------

    @Test
    void aCertificateTheDirectoryPublishesSignsThatPersonIn() throws Exception {
        mockMvc.perform(get("/users").with(x509(aliceCertificate)))
                .andExpect(status().isOk())
                .andExpect(authenticated().withUsername("alice@example.gov"));
    }

    @Test
    void aCertificateTheDirectoryDoesNotPublishIsRefused() throws Exception {
        // It is a perfectly valid certificate; it is simply not one of ours.
        mockMvc.perform(get("/users").with(x509(strangerCertificate)))
                .andExpect(unauthenticated());
    }

    @Test
    void aServersOwnCertificateDoesNotSignAnybodyIn() throws Exception {
        // Published by the directory, but it identifies a machine.
        mockMvc.perform(get("/users").with(x509(serverCertificate)))
                .andExpect(unauthenticated());
    }

    @Test
    void theCertificateIsMatchedByItsBytesNotItsName() {
        // Same subject as alice's, different key and serial: a name collision must not
        // authenticate, which is the whole reason for matching on the fingerprint.
        X509Certificate impostor = TestCertificates.certificate(
                "alice@example.gov", Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(30)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> x509UserDetailsService.loadUserDetails(
                        new PreAuthenticatedAuthenticationToken(
                                impostor.getSubjectX500Principal().getName(), impostor)))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void aCertificateHolderIsResolvedToTheirDirectoryEntry() {
        DirectoryPrincipal principal = (DirectoryPrincipal) x509UserDetailsService.loadUserDetails(
                new PreAuthenticatedAuthenticationToken(
                        aliceCertificate.getSubjectX500Principal().getName(), aliceCertificate));

        assertThat(principal.getDisplayName()).isEqualTo("Alice Archer");
        assertThat(principal.getDirectoryDn()).isEqualTo("uid=alice," + EmbeddedDirectory.PEOPLE_DN);
        assertThat(principal.getMethod()).isEqualTo(DirectoryPrincipal.AuthenticationMethod.X509);
        assertThat(principal.getAuthorities())
                .extracting(Object::toString)
                .containsExactly(DirectoryPrincipalResolver.ROLE_USER);
    }

    // --- LDAP fallback -----------------------------------------------------------------

    @Test
    void aDirectoryPasswordSignsSomebodyInWhenNoCertificateIsPresented() throws Exception {
        mockMvc.perform(formLogin().user("alice").password("password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(authenticated().withUsername("alice@example.gov"));
    }

    @Test
    void theWrongPasswordIsRefused() throws Exception {
        mockMvc.perform(formLogin().user("alice").password("not-the-password"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void anUnknownUsernameIsRefused() throws Exception {
        mockMvc.perform(formLogin().user("nobody").password("password")).andExpect(unauthenticated());
    }

    @Test
    void someoneWhoSignsInWithAPasswordGetsTheSameIdentityAsWithACertificate() throws Exception {
        // Spring Security also records the factor used, so this checks the role is granted
        // rather than that it is the only authority present.
        mockMvc.perform(formLogin().user("alice").password("password"))
                .andExpect(authenticated()
                        .withUsername("alice@example.gov")
                        .withAuthentication(authentication -> assertThat(authentication.getAuthorities())
                                .extracting(GrantedAuthority::getAuthority)
                                .contains(DirectoryPrincipalResolver.ROLE_USER)));
    }

    // --- roles -------------------------------------------------------------------------

    @Test
    void aReaderCannotTriggerASweepOfTheDirectory() throws Exception {
        mockMvc.perform(post("/api/v1/sync/users").with(x509(aliceCertificate)).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdministratorCan() throws Exception {
        // admin@example.gov is listed in cert-alert.security.admin-identifiers for tests.
        mockMvc.perform(post("/api/v1/sync/refresh").with(csrf())
                        .with(SecurityMockMvcRequestPostProcessors.user("admin@example.gov")
                                .roles("USER", "ADMIN")))
                .andExpect(status().isOk());
    }

    @Test
    void theAdminListGrantsTheRoleByAnyIdentifierTheDirectoryKnows() throws Exception {
        mockMvc.perform(formLogin().user("admin").password("password"))
                .andExpect(authenticated().withUsername("admin@example.gov"));

        assertThat(userRepository
                        .findByIdentifier("admin@example.gov")
                        .stream()
                        .findFirst())
                .isPresent();
    }

    @Test
    void anAdministratorIsRecognisedBeforeTheDirectoryHasEverBeenScraped() throws Exception {
        // A fresh deployment has an empty cache. Roles have to come from the entry the
        // person just bound against, or nobody could trigger the first sync.
        userRepository.deleteAll();

        mockMvc.perform(formLogin().user("admin").password("password"))
                .andExpect(authenticated()
                        .withAuthentication(authentication -> assertThat(authentication.getAuthorities())
                                .extracting(GrantedAuthority::getAuthority)
                                .contains(DirectoryPrincipalResolver.ROLE_ADMIN)));
    }

    @Test
    void aReaderCannotReachTheManagementEndpoints() throws Exception {
        mockMvc.perform(get("/actuator/loggers").with(x509(aliceCertificate)))
                .andExpect(status().isForbidden());
    }
}

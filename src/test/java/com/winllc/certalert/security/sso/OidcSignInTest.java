package com.winllc.certalert.security.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jwt.JWTClaimsSet;
import com.winllc.certalert.audit.AuditStore;
import com.winllc.certalert.audit.StoredEvent;
import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestOidcProvider;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Signing in through an OpenID Connect provider: the whole authorization code flow with
 * PKCE, against a provider standing on loopback, and every way an ID token can be wrong.
 */
@SpringBootTest(properties = {
    "cert-alert.sso.oidc.enabled=true",
    "cert-alert.sso.oidc.display-name=Sign in with Test IdP",
    "cert-alert.sso.oidc.client-id=" + TestOidcProvider.CLIENT_ID,
    "cert-alert.sso.oidc.scopes=openid",
    "cert-alert.sso.oidc.groups-claim=groups",
    "cert-alert.sso.oidc.clock-skew=30s",
    "cert-alert.security.groups.admin[0]=cert-alert-admins",
    // The sign-in search covers the whole tree, as it does by default; the sweep does not.
    "cert-alert.security.ldap.user-search-base="
})
@ActiveProfiles("test")
class OidcSignInTest {

    private static EmbeddedDirectory directory;
    private static TestOidcProvider provider;
    private static boolean synced;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private AuditStore auditStore;

    @Autowired
    private SsoIdentityResolver identities;

    private MockMvc mockMvc;

    @BeforeAll
    static void start() {
        directory = new EmbeddedDirectory();
        directory.addUser("olivia", "Olivia Oidc", "olivia@example.gov");
        // Never swept: found in the directory itself when the provider names her.
        directory.addOrganizationalUnit("elsewhere");
        directory.addUserUnder("ou=elsewhere," + EmbeddedDirectory.BASE_DN, "nina", "Nina Newcomer", "nina@example.gov");
        provider = new TestOidcProvider();
    }

    @AfterAll
    static void stop() {
        provider.close();
        directory.close();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", () -> directory.url());
        registry.add("spring.ldap.base", () -> EmbeddedDirectory.BASE_DN);
        registry.add("cert-alert.ldap.user.search-base", () -> "ou=people");
        registry.add("cert-alert.ldap.server.search-base", () -> "ou=servers");
        registry.add("cert-alert.sso.oidc.issuer-uri", () -> provider.issuer());
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

    /** The browser's round trip: to the provider and back with a code. */
    private MvcResult signIn(MockHttpSession session, UnaryOperator<JWTClaimsSet.Builder> token,
            Map<String, Object> extra, boolean otherKey) throws Exception {
        MvcResult start = mockMvc.perform(get("/oauth2/authorization/oidc").session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String location = start.getResponse().getRedirectedUrl();
        assertThat(location).startsWith(provider.issuer() + "/authorize");
        Map<String, String> query = query(location);
        // PKCE, with a client secret or without.
        assertThat(query).containsEntry("code_challenge_method", "S256").containsKey("code_challenge");
        assertThat(query).containsKey("nonce").containsKey("state");
        provider.authorizationRequested(query.get("nonce"), query.get("code_challenge"));
        provider.nextToken(token, extra, otherKey);
        return mockMvc.perform(get("/login/oauth2/code/oidc")
                        .param("code", "the-code")
                        .param("state", query.get("state"))
                        .session(session))
                .andReturn();
    }

    private MvcResult signIn(MockHttpSession session, Map<String, Object> extra) throws Exception {
        return signIn(session, UnaryOperator.identity(), extra, false);
    }

    private static Authentication signedIn(MockHttpSession session) {
        Object context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return context instanceof SecurityContext security ? security.getAuthentication() : null;
    }

    private static List<String> roles(Authentication who) {
        return who.getAuthorities().stream().map(a -> a.getAuthority()).filter(r -> r.startsWith("ROLE_")).toList();
    }

    private void assertRefused(MvcResult result, MockHttpSession session) {
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/login?sso_error");
        assertThat(signedIn(session)).isNull();
    }

    @Test
    void theSignInPageOffersIt() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign in with Test IdP")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/oauth2/authorization/oidc")));
    }

    @Test
    void aPersonTheDirectoryHoldsSignsInByTheirUid() throws Exception {
        long head = auditStore.headSeq();
        MockHttpSession session = new MockHttpSession();

        MvcResult result = signIn(session, Map.of("preferred_username", "olivia"));

        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(provider.lastCodeVerifier()).isNotBlank();
        Authentication who = signedIn(session);
        assertThat(who.getPrincipal()).isInstanceOfSatisfying(DirectoryPrincipal.class, person -> {
            assertThat(person.getMethod()).isEqualTo(DirectoryPrincipal.AuthenticationMethod.OIDC);
            assertThat(person.getDirectoryDn()).isEqualTo("uid=olivia," + EmbeddedDirectory.PEOPLE_DN);
            assertThat(person.getDirectoryUserId()).isNotNull();
        });
        assertThat(roles(who)).containsExactly("ROLE_USER");
        assertThat(logins(head)).singleElement().satisfies(line -> assertThat(line)
                .contains("\"outcome\":\"success\"", "\"method\":\"oidc\""));
        // Signed in, the pages answer.
        mockMvc.perform(get("/users").session(session)).andExpect(status().isOk());
    }

    @Test
    void somebodyNotSweptIsFoundInTheDirectoryItself() throws Exception {
        MockHttpSession session = new MockHttpSession();
        // Outside ou=people, where the sign-in search is not limited.
        MvcResult result = signIn(session, Map.of("preferred_username", "nina"));
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(((DirectoryPrincipal) signedIn(session).getPrincipal()).getDirectoryDn())
                .isEqualTo("uid=nina,ou=elsewhere," + EmbeddedDirectory.BASE_DN);
    }

    @Test
    void aMappedGroupClaimGrantsItsRoleAndAnUnmappedOneNothing() throws Exception {
        MockHttpSession admin = new MockHttpSession();
        signIn(admin, Map.of("preferred_username", "olivia", "groups", List.of("cert-alert-admins")));
        assertThat(roles(signedIn(admin))).contains("ROLE_ADMIN");

        MockHttpSession notAdmin = new MockHttpSession();
        signIn(notAdmin, Map.of("preferred_username", "olivia", "groups", List.of("admins", "Administrators")));
        assertThat(roles(signedIn(notAdmin))).containsExactly("ROLE_USER");
    }

    @Test
    void somebodyTheDirectoryDoesNotHoldIsRefused() throws Exception {
        long head = auditStore.headSeq();
        MockHttpSession session = new MockHttpSession();
        assertRefused(signIn(session, Map.of("preferred_username", "nobody")), session);
        assertThat(logins(head)).singleElement().satisfies(line -> assertThat(line)
                .contains("\"outcome\":\"failure\"", "\"method\":\"oidc\"", "does not hold"));
    }

    /** An address is never who somebody is, however the provider phrases it. */
    @Test
    void anAddressIsNotMatched() throws Exception {
        MockHttpSession session = new MockHttpSession();
        assertRefused(signIn(session, Map.of("email", "olivia@example.gov")), session);
        MockHttpSession named = new MockHttpSession();
        assertRefused(signIn(named, Map.of("preferred_username", "olivia@example.gov")), named);
    }

    @Test
    void aTokenFromAnotherIssuerIsRefused() throws Exception {
        long head = auditStore.headSeq();
        MockHttpSession session = new MockHttpSession();
        assertRefused(signIn(session, claims -> claims.issuer("https://idp.attacker.example"),
                Map.of("preferred_username", "olivia"), false), session);
        assertThat(logins(head)).singleElement().satisfies(line -> assertThat(line)
                .contains("\"outcome\":\"failure\"", "\"method\":\"oidc\""));
    }

    @Test
    void aTokenForAnotherClientIsRefused() throws Exception {
        MockHttpSession session = new MockHttpSession();
        assertRefused(signIn(session, claims -> claims.audience("some-other-client"),
                Map.of("preferred_username", "olivia"), false), session);
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRefused() throws Exception {
        MockHttpSession session = new MockHttpSession();
        assertRefused(signIn(session, UnaryOperator.identity(), Map.of("preferred_username", "olivia"), true), session);
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        long now = System.currentTimeMillis();
        MockHttpSession session = new MockHttpSession();
        assertRefused(signIn(session, claims -> claims
                        .issueTime(new Date(now - 7_200_000))
                        .expirationTime(new Date(now - 3_600_000)),
                Map.of("preferred_username", "olivia"), false), session);
    }

    @Test
    void aTokenForAnotherSignInIsRefused() throws Exception {
        MockHttpSession session = new MockHttpSession();
        assertRefused(signIn(session, claims -> claims.claim("nonce", "not-the-nonce"),
                Map.of("preferred_username", "olivia"), false), session);
    }

    @Test
    void aDnIsMatchedToTheEntryAtIt() {
        assertThat(identities.resolve("uid=olivia," + EmbeddedDirectory.PEOPLE_DN, SsoProperties.Match.DN, List.of(),
                        DirectoryPrincipal.AuthenticationMethod.OIDC))
                .hasValueSatisfying(person -> assertThat(person.getDirectoryUserId()).isNotNull());
        assertThat(identities.resolve("uid=olivia,o=elsewhere", SsoProperties.Match.DN, List.of(),
                        DirectoryPrincipal.AuthenticationMethod.OIDC))
                .isEmpty();
    }

    private List<String> logins(long head) {
        return auditStore.after(head, 1000).stream()
                .filter(event -> "auth.login".equals(event.action()))
                .map(StoredEvent::line)
                .toList();
    }

    private static Map<String, String> query(String url) {
        Map<String, String> values = new LinkedHashMap<>();
        String query = URI.create(url).getRawQuery();
        for (String pair : query.split("&")) {
            int at = pair.indexOf('=');
            values.put(URLDecoder.decode(pair.substring(0, at), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(at + 1), StandardCharsets.UTF_8));
        }
        return values;
    }
}

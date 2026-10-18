package com.winllc.certalert.security.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.audit.AuditStore;
import com.winllc.certalert.audit.StoredEvent;
import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestSamlIdentityProvider;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * Signing in through a SAML identity provider: a request from here, a signed response back -
 * posted as a browser posts it across sites, with no session cookie - and every way a
 * response can be one not to accept.
 */
@SpringBootTest(properties = {
    "cert-alert.sso.saml.enabled=true",
    "cert-alert.sso.saml.display-name=Sign in with Test SAML",
    "cert-alert.sso.saml.groups-attribute=groups",
    "cert-alert.sso.saml.clock-skew=1m",
    "cert-alert.security.groups.auditor[0]=CertAlert-Auditors"
})
@ActiveProfiles("test")
class SamlSignInTest {

    private static final String ACS = "http://localhost/login/saml2/sso/saml";
    private static final String SP_ENTITY_ID = "http://localhost/saml2/service-provider-metadata/saml";

    private static EmbeddedDirectory directory;
    private static TestSamlIdentityProvider provider;
    private static Path metadata;
    private static boolean synced;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private AuditStore auditStore;

    private MockMvc mockMvc;

    @BeforeAll
    static void startProvider() throws Exception {
        directory = new EmbeddedDirectory();
        directory.addUser("sally", "Sally Saml", "sally@example.gov");
        provider = new TestSamlIdentityProvider();
        metadata = provider.writeMetadata(Files.createTempDirectory("saml-idp"));
    }

    @AfterAll
    static void stopProvider() throws Exception {
        directory.close();
        Files.deleteIfExists(metadata);
        Files.deleteIfExists(metadata.getParent());
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", () -> directory.url());
        registry.add("spring.ldap.base", () -> EmbeddedDirectory.BASE_DN);
        registry.add("cert-alert.ldap.user.search-base", () -> "ou=people");
        registry.add("cert-alert.ldap.server.search-base", () -> "ou=servers");
        registry.add("cert-alert.sso.saml.metadata-location", () -> metadata.toUri().toString());
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

    /** A sign-in started here: the request's id, and the RelayState that comes back with the answer. */
    private record Started(String requestId, String relayState) {}

    private Started signInStarted() throws Exception {
        MvcResult redirect = mockMvc.perform(get("/saml2/authenticate/saml"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String location = redirect.getResponse().getRedirectedUrl();
        assertThat(location).startsWith(TestSamlIdentityProvider.SSO_URL);
        Map<String, String> query = query(location);
        return new Started(TestSamlIdentityProvider.requestId(query.get("SAMLRequest")), query.get("RelayState"));
    }

    /** A good answer to it, for the test to spoil. */
    private static TestSamlIdentityProvider.Answer answer(Started started, String nameId) {
        TestSamlIdentityProvider.Answer answer = new TestSamlIdentityProvider.Answer();
        answer.inResponseTo = started.requestId();
        answer.destination = ACS;
        answer.audience = SP_ENTITY_ID;
        answer.nameId = nameId;
        return answer;
    }

    /** Posted as the identity provider's page posts it: cross-site, so with no session cookie. */
    private MvcResult answerWith(String response, String relayState) throws Exception {
        var request = post("/login/saml2/sso/saml").param("SAMLResponse", response);
        if (relayState != null) {
            request.param("RelayState", relayState);
        }
        return mockMvc.perform(request).andReturn();
    }

    private static Authentication signedIn(MvcResult result) {
        HttpSession session = result.getRequest().getSession(false);
        Object context = session == null ? null
                : session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return context instanceof SecurityContext security ? security.getAuthentication() : null;
    }

    private static void assertRefused(MvcResult result) {
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/login?sso_error");
        assertThat(signedIn(result)).isNull();
    }

    @Test
    void theSignInPageOffersItAndTheMetadataIsPublished() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign in with Test SAML")));
        mockMvc.perform(get("/saml2/service-provider-metadata/saml"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(SP_ENTITY_ID)));
    }

    @Test
    void aSignedAnswerForSomebodyTheDirectoryHoldsSignsThemIn() throws Exception {
        long head = auditStore.headSeq();
        Started started = signInStarted();

        MvcResult result = answerWith(provider.respond(answer(started, "sally")), started.relayState());

        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/");
        Authentication who = signedIn(result);
        assertThat(who.getPrincipal()).isInstanceOfSatisfying(DirectoryPrincipal.class, person -> {
            assertThat(person.getMethod()).isEqualTo(DirectoryPrincipal.AuthenticationMethod.SAML);
            assertThat(person.getDirectoryDn()).isEqualTo("uid=sally," + EmbeddedDirectory.PEOPLE_DN);
        });
        assertThat(roles(who)).containsExactly("ROLE_USER");
        assertThat(logins(head)).singleElement().satisfies(line -> assertThat(line)
                .contains("\"outcome\":\"success\"", "\"method\":\"saml\""));
    }

    @Test
    void aMappedGroupAttributeGrantsItsRole() throws Exception {
        Started started = signInStarted();
        TestSamlIdentityProvider.Answer answer = answer(started, "sally");
        answer.attributes = Map.of("groups", List.of("CertAlert-Auditors"));
        assertThat(roles(signedIn(answerWith(provider.respond(answer), started.relayState())))).contains("ROLE_AUDITOR");
    }

    @Test
    void theSameAnswerTwiceIsRefusedTheSecondTime() throws Exception {
        Started started = signInStarted();
        String response = provider.respond(answer(started, "sally"));
        assertThat(signedIn(answerWith(response, started.relayState()))).isNotNull();

        assertRefused(answerWith(response, started.relayState()));
    }

    @Test
    void anAnswerToNothingThisApplicationAskedIsRefused() throws Exception {
        long head = auditStore.headSeq();
        Started started = signInStarted();
        TestSamlIdentityProvider.Answer unsolicited = answer(started, "sally");
        unsolicited.inResponseTo = null;
        assertRefused(answerWith(provider.respond(unsolicited), null));
        assertRefused(answerWith(provider.respond(unsolicited), started.relayState()));
        assertThat(logins(head)).allSatisfy(line -> assertThat(line)
                .contains("\"outcome\":\"failure\"", "\"method\":\"saml\""));
    }

    @Test
    void anAnswerToAnotherRequestIsRefused() throws Exception {
        Started started = signInStarted();
        TestSamlIdentityProvider.Answer answer = answer(started, "sally");
        answer.inResponseTo = "_some-other-request";
        assertRefused(answerWith(provider.respond(answer), started.relayState()));
    }

    @Test
    void anAnswerFromAnotherIssuerIsRefused() throws Exception {
        Started started = signInStarted();
        TestSamlIdentityProvider.Answer answer = answer(started, "sally");
        answer.issuer = "https://idp.attacker.example/saml";
        assertRefused(answerWith(provider.respond(answer), started.relayState()));
    }

    @Test
    void anUnsignedAnswerIsRefused() throws Exception {
        Started started = signInStarted();
        TestSamlIdentityProvider.Answer answer = answer(started, "sally");
        answer.signed = false;
        assertRefused(answerWith(provider.respond(answer), started.relayState()));
    }

    @Test
    void anAnswerSignedWithAnotherKeyIsRefused() throws Exception {
        Started started = signInStarted();
        TestSamlIdentityProvider.Answer answer = answer(started, "sally");
        answer.signedByAnotherKey = true;
        assertRefused(answerWith(provider.respond(answer), started.relayState()));
    }

    @Test
    void anAnswerChangedAfterSigningIsRefused() throws Exception {
        Started started = signInStarted();
        directory.addUser("mallory", "Mallory Changed", "mallory@example.gov");
        String response = provider.respond(answer(started, "sally"));
        assertRefused(answerWith(TestSamlIdentityProvider.tampered(response, ">sally<", ">mallory<"), started.relayState()));
    }

    @Test
    void anAnswerOutOfDateIsRefused() throws Exception {
        Started started = signInStarted();
        TestSamlIdentityProvider.Answer answer = answer(started, "sally");
        answer.notOnOrAfter = Instant.now().minus(Duration.ofMinutes(10));
        assertRefused(answerWith(provider.respond(answer), started.relayState()));
    }

    @Test
    void anAnswerForAnotherServiceProviderIsRefused() throws Exception {
        Started started = signInStarted();
        TestSamlIdentityProvider.Answer answer = answer(started, "sally");
        answer.audience = "https://another-sp.example/saml";
        assertRefused(answerWith(provider.respond(answer), started.relayState()));
    }

    @Test
    void somebodyTheDirectoryDoesNotHoldIsRefused() throws Exception {
        long head = auditStore.headSeq();
        Started started = signInStarted();
        assertRefused(answerWith(provider.respond(answer(started, "stranger")), started.relayState()));
        assertThat(logins(head)).singleElement().satisfies(line -> assertThat(line)
                .contains("\"outcome\":\"failure\"", "\"method\":\"saml\"", "does not hold"));
    }

    private static List<String> roles(Authentication who) {
        return who.getAuthorities().stream().map(a -> a.getAuthority()).filter(r -> r.startsWith("ROLE_")).toList();
    }

    private List<String> logins(long head) {
        return auditStore.after(head, 1000).stream()
                .filter(event -> "auth.login".equals(event.action()))
                .map(StoredEvent::line)
                .toList();
    }

    private static Map<String, String> query(String url) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            int at = pair.indexOf('=');
            values.put(URLDecoder.decode(pair.substring(0, at), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(at + 1), StandardCharsets.UTF_8));
        }
        return values;
    }
}

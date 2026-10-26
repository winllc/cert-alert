package com.winllc.certalert.security.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestSamlIdentityProvider;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.saml2.provider.service.authentication.Saml2AssertionAuthentication;
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
 * SAML single logout, both ways, with a signing key and an identity provider whose metadata
 * names a SingleLogoutService: signing out here sends the provider a signed LogoutRequest for
 * the NameID and session it signed in, and its answer lands on the sign-in page; a signed
 * LogoutRequest from the provider ends the session here and is answered, signed. Messages not
 * signed by the provider, or for somebody else, end nothing.
 */
@SpringBootTest(properties = {
    "cert-alert.sso.saml.enabled=true",
    "cert-alert.sso.saml.single-logout=true",
    "cert-alert.sso.public-url=" + SamlSingleLogoutTest.PUBLIC_URL
})
@ActiveProfiles("test")
class SamlSingleLogoutTest {

    static final String PUBLIC_URL = "https://certalert.example.gov";
    private static final String ACS = PUBLIC_URL + "/login/saml2/sso/saml";
    private static final String SP_ENTITY_ID = PUBLIC_URL + "/saml2/service-provider-metadata/saml";
    private static final String SLO = PUBLIC_URL + SsoSignIn.SAML_LOGOUT_PATH;

    private static EmbeddedDirectory directory;
    private static TestSamlIdentityProvider provider;
    private static Path files;
    private static X509Certificate serviceProvider;
    private static boolean synced;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectorySyncService syncService;

    private MockMvc mockMvc;

    @BeforeAll
    static void startProvider() throws Exception {
        directory = new EmbeddedDirectory();
        directory.addUser("sally", "Sally Saml", "sally@example.gov");
        directory.addUser("mallory", "Mallory Other", "mallory@example.gov");
        provider = new TestSamlIdentityProvider();
        files = Files.createTempDirectory("saml-slo");
        provider.writeMetadata(files, true);
        serviceProvider = TestSamlIdentityProvider.writeServiceProviderKey(files);
    }

    @AfterAll
    static void stopProvider() throws Exception {
        directory.close();
        try (var listing = Files.list(files)) {
            for (Path file : listing.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(files);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", () -> directory.url());
        registry.add("spring.ldap.base", () -> EmbeddedDirectory.BASE_DN);
        registry.add("cert-alert.ldap.user.search-base", () -> "ou=people");
        registry.add("cert-alert.ldap.server.search-base", () -> "ou=servers");
        registry.add("cert-alert.sso.saml.metadata-location", () -> files.resolve("idp-metadata.xml").toUri().toString());
        registry.add("cert-alert.sso.saml.signing-key-location", () -> files.resolve("sp-signing.key").toUri().toString());
        registry.add("cert-alert.sso.saml.signing-certificate-location",
                () -> files.resolve("sp-signing.crt").toUri().toString());
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

    /** Signs {@code nameId} in through the provider, with its session {@code sessionIndex}: the browser's session. */
    private MockHttpSession signIn(String nameId, String sessionIndex) throws Exception {
        MvcResult redirect = mockMvc.perform(get("/saml2/authenticate/saml"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String setCookie = redirect.getResponse().getHeaders("Set-Cookie").stream()
                .filter(header -> header.startsWith(JdbcSaml2AuthenticationRequests.COOKIE + "="))
                .findFirst()
                .orElseThrow();
        Cookie binding = new Cookie(JdbcSaml2AuthenticationRequests.COOKIE,
                setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';')));
        Map<String, String> query = TestSamlIdentityProvider.queryOf(redirect.getResponse().getRedirectedUrl());

        TestSamlIdentityProvider.Answer answer = new TestSamlIdentityProvider.Answer();
        answer.inResponseTo = TestSamlIdentityProvider.requestId(query.get("SAMLRequest"));
        answer.destination = ACS;
        answer.audience = SP_ENTITY_ID;
        answer.nameId = nameId;
        answer.sessionIndex = sessionIndex;
        MvcResult signedIn = mockMvc.perform(post("/login/saml2/sso/saml")
                        .param("SAMLResponse", provider.respond(answer))
                        .param("RelayState", query.get("RelayState"))
                        .cookie(binding))
                .andReturn();
        assertThat(signedIn.getResponse().getRedirectedUrl()).isEqualTo("/");
        MockHttpSession session = (MockHttpSession) signedIn.getRequest().getSession(false);
        assertThat(session).isNotNull();
        SecurityContext security = (SecurityContext)
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(security.getAuthentication()).isInstanceOf(Saml2AssertionAuthentication.class);
        return session;
    }

    private static String attribute(String xml, String name) {
        Matcher value = Pattern.compile("\\s" + name + "=\"([^\"]+)\"").matcher(xml);
        assertThat(value.find()).as(name + " in " + xml).isTrue();
        return value.group(1);
    }

    @Test
    void theMetadataNamesTheLogoutServiceAtThePublicAddress() throws Exception {
        mockMvc.perform(get("/saml2/service-provider-metadata/saml").with(request -> {
                    request.setServerName("sp-b.attacker.example");
                    return request;
                }))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("SingleLogoutService")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Location=\"" + SLO + "\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("attacker"))));
    }

    @Test
    void signingOutHereSignsOutAtTheProviderAndItsAnswerLandsOnTheSignInPage() throws Exception {
        MockHttpSession session = signIn("sally", "_idp-session-1");

        MvcResult out = mockMvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(session.isInvalid()).as("the session here has ended").isTrue();
        String location = out.getResponse().getRedirectedUrl();
        assertThat(location).startsWith(TestSamlIdentityProvider.SLO_URL + "?");
        String request = TestSamlIdentityProvider.inflated(location, "SAMLRequest");
        assertThat(request).contains("LogoutRequest", ">sally<", ">_idp-session-1<", ">" + SP_ENTITY_ID + "<");
        assertThat(attribute(request, "Destination")).isEqualTo(TestSamlIdentityProvider.SLO_URL);
        assertThat(TestSamlIdentityProvider.signedBy(location, serviceProvider)).as("signed by this SP").isTrue();

        // The provider's answer, to the browser's new session that holds the request.
        MockHttpSession waiting = (MockHttpSession) out.getRequest().getSession(false);
        assertThat(waiting).isNotNull();
        String answer = provider.logoutResponseQuery(SLO, attribute(request, "ID"),
                TestSamlIdentityProvider.queryOf(location).get("RelayState"), false);
        mockMvc.perform(get(URI.create(SsoSignIn.SAML_LOGOUT_PATH + "?" + answer)).session(waiting))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/login?logout"));
    }

    @Test
    void anAnswerToAnotherRequestOrNotSignedByTheProviderIsRefused() throws Exception {
        for (boolean otherKey : new boolean[] {false, true}) {
            MockHttpSession session = signIn("sally", "_idp-session-2");
            MvcResult out = mockMvc.perform(post("/logout").session(session).with(csrf())).andReturn();
            String request = TestSamlIdentityProvider.inflated(out.getResponse().getRedirectedUrl(), "SAMLRequest");
            MockHttpSession waiting = (MockHttpSession) out.getRequest().getSession(false);
            String inResponseTo = otherKey ? attribute(request, "ID") : "_some-other-request";
            String answer = provider.logoutResponseQuery(SLO, inResponseTo,
                    TestSamlIdentityProvider.queryOf(out.getResponse().getRedirectedUrl()).get("RelayState"), otherKey);
            MvcResult refused = mockMvc.perform(get(URI.create(SsoSignIn.SAML_LOGOUT_PATH + "?" + answer)).session(waiting))
                    .andReturn();
            assertThat(refused.getResponse().getStatus()).as("refused, other key: " + otherKey).isEqualTo(401);
        }
    }

    @Test
    void signingOutAtTheProviderEndsTheSessionHereAndIsAnsweredSigned() throws Exception {
        MockHttpSession session = signIn("sally", "_idp-session-3");
        String id = "_lr-" + UUID.randomUUID();
        String query = provider.logoutRequestQuery(SLO, "sally", "_idp-session-3", id, false);

        MvcResult result = mockMvc.perform(get(URI.create(SsoSignIn.SAML_LOGOUT_PATH + "?" + query)).session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        assertThat(session.isInvalid()).as("the session here has ended").isTrue();
        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).startsWith(TestSamlIdentityProvider.SLO_URL + "?");
        String answer = TestSamlIdentityProvider.inflated(location, "SAMLResponse");
        assertThat(attribute(answer, "InResponseTo")).isEqualTo(id);
        assertThat(attribute(answer, "Destination")).isEqualTo(TestSamlIdentityProvider.SLO_URL);
        assertThat(answer).contains("urn:oasis:names:tc:SAML:2.0:status:Success");
        assertThat(TestSamlIdentityProvider.queryOf(location)).containsEntry("RelayState", "idp-relay");
        assertThat(TestSamlIdentityProvider.signedBy(location, serviceProvider)).as("signed by this SP").isTrue();
    }

    @Test
    void aLogoutRequestNotSignedByTheProviderOrForSomebodyElseEndsNothing() throws Exception {
        MockHttpSession session = signIn("sally", "_idp-session-4");

        String forged = provider.logoutRequestQuery(SLO, "sally", "_idp-session-4", "_lr-forged", true);
        MvcResult refused = mockMvc.perform(get(URI.create(SsoSignIn.SAML_LOGOUT_PATH + "?" + forged)).session(session))
                .andReturn();
        assertThat(session.isInvalid()).as("a forged request ended the session").isFalse();
        assertNotASuccess(refused);

        String other = provider.logoutRequestQuery(SLO, "mallory", null, "_lr-other", false);
        MvcResult notHers = mockMvc.perform(get(URI.create(SsoSignIn.SAML_LOGOUT_PATH + "?" + other)).session(session))
                .andReturn();
        assertThat(session.isInvalid()).as("somebody else's sign-out ended this session").isFalse();
        assertNotASuccess(notHers);

        String elsewhere = provider.logoutRequestQuery("https://sp-b.attacker.example/logout/saml2/slo", "sally",
                null, "_lr-elsewhere", false);
        mockMvc.perform(get(URI.create(SsoSignIn.SAML_LOGOUT_PATH + "?" + elsewhere)).session(session)).andReturn();
        assertThat(session.isInvalid()).as("a request for another SP ended this session").isFalse();

        // Still signed in.
        assertThat(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY)).isNotNull();
    }

    /** An error answer, or none at all - never a Success LogoutResponse. */
    private static void assertNotASuccess(MvcResult result) throws Exception {
        String location = result.getResponse().getRedirectedUrl();
        if (location != null && location.contains("SAMLResponse=")) {
            // The top-level status is the first StatusCode.
            assertThat(attribute(TestSamlIdentityProvider.inflated(location, "SAMLResponse"), "Value"))
                    .isNotEqualTo("urn:oasis:names:tc:SAML:2.0:status:Success");
        } else {
            assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        }
    }
}

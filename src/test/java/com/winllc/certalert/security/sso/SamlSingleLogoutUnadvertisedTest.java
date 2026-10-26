package com.winllc.certalert.security.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestSamlIdentityProvider;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Single logout configured - the setting and a signing key - but the identity provider's
 * metadata names no SingleLogoutService: signing out ends the session here and lands on the
 * sign-in page, as without single logout, rather than failing.
 */
@SpringBootTest(properties = {
    "cert-alert.sso.saml.enabled=true",
    "cert-alert.sso.saml.single-logout=true",
    "cert-alert.sso.public-url=https://certalert.example.gov"
})
@ActiveProfiles("test")
class SamlSingleLogoutUnadvertisedTest {

    private static EmbeddedDirectory directory;
    private static TestSamlIdentityProvider provider;
    private static Path files;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectorySyncService syncService;

    @BeforeAll
    static void startProvider() throws Exception {
        directory = new EmbeddedDirectory();
        directory.addUser("sally", "Sally Saml", "sally@example.gov");
        provider = new TestSamlIdentityProvider();
        files = Files.createTempDirectory("saml-no-slo");
        provider.writeMetadata(files, false);
        TestSamlIdentityProvider.writeServiceProviderKey(files);
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

    @Test
    void signingOutStaysHere() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        syncService.syncUsers();

        MvcResult redirect = mockMvc.perform(get("/saml2/authenticate/saml")).andReturn();
        String setCookie = redirect.getResponse().getHeaders("Set-Cookie").stream()
                .filter(header -> header.startsWith(JdbcSaml2AuthenticationRequests.COOKIE + "="))
                .findFirst()
                .orElseThrow();
        Map<String, String> query = TestSamlIdentityProvider.queryOf(redirect.getResponse().getRedirectedUrl());
        TestSamlIdentityProvider.Answer answer = new TestSamlIdentityProvider.Answer();
        answer.inResponseTo = TestSamlIdentityProvider.requestId(query.get("SAMLRequest"));
        answer.destination = "https://certalert.example.gov/login/saml2/sso/saml";
        answer.audience = "https://certalert.example.gov/saml2/service-provider-metadata/saml";
        answer.nameId = "sally";
        answer.sessionIndex = "_idp-session";
        MvcResult signedIn = mockMvc.perform(post("/login/saml2/sso/saml")
                        .param("SAMLResponse", provider.respond(answer))
                        .param("RelayState", query.get("RelayState"))
                        .cookie(new Cookie(JdbcSaml2AuthenticationRequests.COOKIE,
                                setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';')))))
                .andReturn();
        assertThat(signedIn.getResponse().getRedirectedUrl()).isEqualTo("/");
        MockHttpSession session = (MockHttpSession) signedIn.getRequest().getSession(false);

        mockMvc.perform(post("/logout").session(session).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/login?logout"));
        assertThat(session.isInvalid()).isTrue();
    }
}

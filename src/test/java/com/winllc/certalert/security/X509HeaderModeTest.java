package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.audit.AuditStore;
import com.winllc.certalert.audit.AuthenticationAudit;
import com.winllc.certalert.audit.StoredEvent;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestCa;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Certificates forwarded by a TLS-terminating proxy ({@code ENS_X509_SOURCE=header}):
 * believed only from a trusted address, with the proxy secret, and chaining to a trusted CA.
 */
@SpringBootTest(properties = {
    "ENS_X509_SOURCE=header",
    "ENS_X509_PROXY_SECRET=" + X509HeaderModeTest.SECRET,
    "ENS_X509_TRUSTED_PROXIES=127.0.0.1/32"
})
@ActiveProfiles("test")
class X509HeaderModeTest {

    static final String SECRET = "0123456789abcdef0123456789abcdef-proxy";

    private static EmbeddedDirectory directory;
    private static TestCa ca;
    private static TestCa.Issued judy;
    private static Path caFile;
    private static boolean synced;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private AuditStore auditStore;

    @Autowired
    private AuthenticationAudit authenticationAudit;

    private MockMvc mockMvc;

    @BeforeAll
    static void start() throws Exception {
        ca = new TestCa("Example Proxy CA");
        caFile = Files.createTempFile("trusted-ca", ".pem");
        Files.writeString(caFile, ca.certificatePem());
        directory = new EmbeddedDirectory();
        judy = ca.issue("judy@example.gov", Instant.now().minus(Duration.ofDays(10)),
                Instant.now().plus(Duration.ofDays(300)), null, null);
        directory.addUser("judy", "Judy Header", "judy@example.gov", judy.der());
    }

    @AfterAll
    static void stop() throws Exception {
        directory.close();
        Files.deleteIfExists(caFile);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", () -> directory.url());
        registry.add("spring.ldap.base", () -> EmbeddedDirectory.BASE_DN);
        registry.add("cert-alert.ldap.user.search-base", () -> "ou=people");
        registry.add("cert-alert.ldap.server.search-base", () -> "ou=servers");
        registry.add("ENS_X509_TRUSTED_CA_FILE", () -> caFile.toString());
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

    @Test
    void aForwardedCertificateFromTheProxySignsIn() throws Exception {
        mockMvc.perform(forwarded(judy.certificate(), SECRET))
                .andExpect(status().isOk())
                .andExpect(authenticated().withUsername("judy@example.gov"));
    }

    @Test
    void withoutTheSecretItIsNotBelievedAndTheRefusalIsAudited() throws Exception {
        List<String> refused = refusals(() -> mockMvc
                .perform(forwarded(judy.certificate(), "not-the-secret"))
                .andExpect(unauthenticated()));
        assertThat(refused).singleElement().satisfies(line -> assertThat(line)
                .contains("\"reason\":\"certificate header not from a trusted proxy\"", "\"method\":\"x509\""));

        mockMvc.perform(get("/users").header("X-SSL-Client-Cert", escapedPem(judy.certificate())))
                .andExpect(unauthenticated());
    }

    @Test
    void norFromAnAddressThatIsNotTheProxy() throws Exception {
        mockMvc.perform(forwarded(judy.certificate(), SECRET).with(request -> {
                    request.setRemoteAddr("10.1.2.3");
                    return request;
                }))
                .andExpect(unauthenticated());
    }

    @Test
    void aCertificateFromAnotherCaIsNotTrusted() throws Exception {
        TestCa elsewhere = new TestCa("Some Other CA");
        TestCa.Issued impostor = elsewhere.issue("judy@example.gov", Instant.now().minus(Duration.ofDays(1)),
                Instant.now().plus(Duration.ofDays(30)), null, null);

        List<String> refused = refusals(() -> mockMvc
                .perform(forwarded(impostor.certificate(), SECRET))
                .andExpect(unauthenticated()));
        assertThat(refused).singleElement()
                .satisfies(line -> assertThat(line).contains("\"reason\":\"certificate not trusted\""));
    }

    @Test
    void aTlsCertificateIsIgnoredInHeaderMode() throws Exception {
        mockMvc.perform(get("/users").with(x509(judy.certificate()))).andExpect(unauthenticated());
    }

    private static MockHttpServletRequestBuilder forwarded(X509Certificate certificate, String secret) {
        return get("/users")
                .header("X-SSL-Client-Cert", escapedPem(certificate))
                .header(ForwardedCertificates.PROXY_SECRET_HEADER, secret);
    }

    /** As nginx's $ssl_client_escaped_cert sends it. */
    private static String escapedPem(X509Certificate certificate) {
        try {
            String pem = "-----BEGIN CERTIFICATE-----\n"
                    + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(certificate.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
            return URLEncoder.encode(pem, StandardCharsets.UTF_8).replace("+", "%20");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> refusals(ThrowingRunnable request) throws Exception {
        authenticationAudit.flush();
        long head = auditStore.headSeq();
        request.run();
        authenticationAudit.flush();
        return auditStore.after(head, 1000).stream()
                .filter(event -> "auth.login".equals(event.action()))
                .map(StoredEvent::line)
                .toList();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

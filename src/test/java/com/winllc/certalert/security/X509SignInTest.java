package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.x509;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.audit.AuditStore;
import com.winllc.certalert.audit.AuthenticationAudit;
import com.winllc.certalert.revocation.SignInRevocation;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestCa;
import com.winllc.certalert.support.TestOcspResponder;
import java.math.BigInteger;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The ENS baseline's certificate checks, through the real filter chain: revocation at
 * sign-in, the issuer allowlist, and finding the holder of a certificate the directory does
 * not publish by what it says about them.
 */
@SpringBootTest
@ActiveProfiles("test")
class X509SignInTest {

    private static EmbeddedDirectory directory;
    private static TestCa ca;
    private static TestOcspResponder responder;
    /** Published by grace's entry, and naming the test responder. */
    private static TestCa.Issued graceCertificate;
    /** Not published: carries a UPN for the user filter to find hank by. */
    private static X509Certificate hankCac;
    private static boolean synced;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private EnsX509Properties x509;

    @Autowired
    private SecurityProperties security;

    @Autowired
    private SignInRevocation revocation;

    @Autowired
    private AuditStore auditStore;

    @Autowired
    private AuthenticationAudit authenticationAudit;

    private MockMvc mockMvc;

    @BeforeAll
    static void start() throws Exception {
        ca = new TestCa("Example Smart Card CA");
        responder = new TestOcspResponder(ca);
        directory = new EmbeddedDirectory();
        Instant now = Instant.now();
        graceCertificate = ca.issue("grace@example.gov", now.minus(Duration.ofDays(10)),
                now.plus(Duration.ofDays(300)), null, responder.url());
        directory.addUser("grace", "Grace Revocation", "grace@example.gov", graceCertificate.der());
        // Found by the filter (uid={edipi}); and two entries sharing an address, to be ambiguous.
        directory.addUser("1234567890", "Hank Mapping", "hank@example.gov");
        directory.addUser("twin1", "Twin One", "twins@example.gov");
        directory.addUser("twin2", "Twin Two", "twins@example.gov");
        hankCac = cac("CN=MAPPING.HANK.1234567890", "1234567890@mil", "twins@example.gov");
    }

    @AfterAll
    static void stop() {
        responder.close();
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
        revocation.forget();
    }

    @AfterEach
    void restore() {
        x509.setRevocation("off");
        x509.setRevocationFail("closed");
        x509.setAllowedIssuers(null);
        x509.setUserFilter(null);
        security.getX509().setRequireKnownCertificate(true);
        revocation.forget();
    }

    @Test
    void aGoodCertificateSignsInWithRevocationOn() throws Exception {
        x509.setRevocation("ocsp");
        int asked = responder.requestCount();

        // The issuer comes with the chain the browser presented.
        mockMvc.perform(get("/users").with(x509(graceCertificate.certificate(), ca.certificate())))
                .andExpect(status().isOk())
                .andExpect(authenticated().withUsername("grace@example.gov"));
        assertThat(responder.requestCount()).isEqualTo(asked + 1);
    }

    @Test
    void aRevokedOneIsRefusedAndAudited() throws Exception {
        x509.setRevocation("ocsp");
        // Checked before anything else, so whether the directory publishes it does not matter.
        TestCa.Issued ivy = ca.issue("ivy@example.gov", Instant.now().minus(Duration.ofDays(10)),
                Instant.now().plus(Duration.ofDays(300)), null, responder.url());
        responder.revoke(ivy.serial(), Instant.now().minus(Duration.ofHours(1)));

        List<String> refused = refusals(() -> mockMvc
                .perform(get("/users").with(x509(ivy.certificate(), ca.certificate())))
                .andExpect(unauthenticated()));
        assertThat(refused).singleElement().satisfies(line -> assertThat(line)
                .contains("\"reason\":\"certificate revoked\"", "\"outcome\":\"failure\"", "\"method\":\"x509\"",
                        "\"severity\":\"alert\""));
    }

    @Test
    void anUnknownStatusIsRefusedUnlessFailOpen() throws Exception {
        x509.setRevocation("ocsp");
        x509.setOcspResponderUrl("http://localhost:1/ocsp");
        try {
            List<String> refused = refusals(() -> mockMvc
                    .perform(get("/users").with(x509(graceCertificate.certificate(), ca.certificate())))
                    .andExpect(unauthenticated()));
            assertThat(refused).singleElement()
                    .satisfies(line -> assertThat(line).contains("\"reason\":\"revocation unknown\""));

            x509.setRevocationFail("open");
            revocation.forget();
            mockMvc.perform(get("/users").with(x509(graceCertificate.certificate(), ca.certificate())))
                    .andExpect(authenticated().withUsername("grace@example.gov"));
        } finally {
            x509.setOcspResponderUrl(null);
        }
    }

    @Test
    void onlyTheAllowedIssuersSignIn() throws Exception {
        x509.setAllowedIssuers("CN=DoD ID CA-70, OU=PKI, OU=DoD, O=U.S. Government, C=US; CN=Somebody Else");
        List<String> refused = refusals(() -> mockMvc
                .perform(get("/users").with(x509(graceCertificate.certificate())))
                .andExpect(unauthenticated()));
        assertThat(refused).singleElement()
                .satisfies(line -> assertThat(line).contains("\"reason\":\"issuer not allowed\""));

        // Compared as names: spacing and case are not a different issuer.
        x509.setAllowedIssuers("CN=DoD ID CA-70,OU=PKI,OU=DoD,O=U.S. Government,C=US;cn=example smart card ca");
        mockMvc.perform(get("/users").with(x509(graceCertificate.certificate())))
                .andExpect(authenticated().withUsername("grace@example.gov"));
    }

    @Test
    void theUserFilterFindsTheHolderOfAnUnpublishedCertificate() throws Exception {
        security.getX509().setRequireKnownCertificate(false);
        x509.setUserFilter("(uid={edipi})");

        mockMvc.perform(get("/users").with(x509(hankCac)))
                .andExpect(authenticated().withUsername("hank@example.gov"));
    }

    @Test
    void aFilterThatFindsMoreThanOneOrNobodySignsNobodyIn() throws Exception {
        security.getX509().setRequireKnownCertificate(false);

        x509.setUserFilter("(icEmail={email})");
        mockMvc.perform(get("/users").with(x509(hankCac))).andExpect(unauthenticated());

        x509.setUserFilter("(uid={uuid})");
        mockMvc.perform(get("/users").with(x509(hankCac))).andExpect(unauthenticated());
    }

    @Test
    void theFilterIsNotConsultedWhileOnlyPublishedCertificatesSignIn() throws Exception {
        x509.setUserFilter("(uid={edipi})");

        mockMvc.perform(get("/users").with(x509(hankCac))).andExpect(unauthenticated());
    }

    private List<String> refusals(ThrowingRunnable request) throws Exception {
        authenticationAudit.flush();
        long head = auditStore.headSeq();
        request.run();
        authenticationAudit.flush();
        return auditStore.after(head, 1000).stream()
                .filter(event -> "auth.login".equals(event.action()))
                .map(com.winllc.certalert.audit.StoredEvent::line)
                .toList();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** A CAC-shaped certificate: EDIPI in the CN, a UPN, an address. */
    private static X509Certificate cac(String subject, String upn, String email) throws Exception {
        java.security.KeyPair keys = com.winllc.certalert.support.TestCertificates.sharedKeyPair();
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                new X500Name("CN=Example Smart Card CA"),
                BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minus(Duration.ofDays(1))),
                Date.from(now.plus(Duration.ofDays(30))),
                new X500Name(subject),
                keys.getPublic());
        ASN1Encodable[] otherName = {
            new ASN1ObjectIdentifier("1.3.6.1.4.1.311.20.2.3"), new DERTaggedObject(true, 0, new DERUTF8String(upn))
        };
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(new GeneralName[] {
            new GeneralName(GeneralName.otherName, new DERSequence(otherName)),
            new GeneralName(GeneralName.rfc822Name, email)
        }));
        return new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
    }
}

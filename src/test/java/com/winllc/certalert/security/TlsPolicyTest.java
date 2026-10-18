package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServer;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * The TLS policy in application.yml, held to a real handshake: the {@code server.ssl} it
 * declares is bound exactly as Spring Boot binds it and handed to the same Tomcat factory,
 * and a client then tries what it should and should not get.
 */
class TlsPolicyTest {

    /**
     * NIST SP 800-52 Rev. 2's approved suites, for an RSA or ECDSA server certificate: TLS
     * 1.3's AES-GCM and AES-CCM, and TLS 1.2's ephemeral (EC)DHE with AES-GCM, AES-CCM or
     * AES-CBC. The policy may be stricter than this; it may never be looser.
     */
    private static final Set<String> FIPS_APPROVED = Set.of(
            "TLS_AES_256_GCM_SHA384",
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_128_CCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_CCM",
            "TLS_ECDHE_ECDSA_WITH_AES_128_CCM",
            "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
            "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
            "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256");

    private static final String PASSWORD = "changeit";

    @TempDir
    static Path directory;

    private static Ssl ssl;
    private static WebServer server;
    private static SSLContext client;

    @BeforeAll
    static void start() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source :
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        ssl = Binder.get(environment).bind("server.ssl", Ssl.class).get();

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        X500Name name = new X500Name("CN=localhost");
        Instant now = Instant.now();
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(
                        name, BigInteger.TWO, Date.from(now.minusSeconds(60)), Date.from(now.plusSeconds(86400)),
                        name, pair.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(pair.getPrivate())));

        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setKeyEntry("server", pair.getPrivate(), PASSWORD.toCharArray(), new Certificate[] {certificate});
        Path keystore = directory.resolve("keystore.p12");
        try (OutputStream out = Files.newOutputStream(keystore)) {
            keys.store(out, PASSWORD.toCharArray());
        }

        // Everything from application.yml but the stores: this test's own, and no client
        // certificates asked for.
        ssl.setEnabled(true);
        ssl.setKeyStore(keystore.toUri().toString());
        ssl.setKeyStorePassword(PASSWORD);
        ssl.setKeyStoreType("PKCS12");
        ssl.setTrustStore(null);
        ssl.setClientAuth(Ssl.ClientAuth.NONE);

        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
        factory.setAddress(InetAddress.getLoopbackAddress());
        factory.setSsl(ssl);
        server = factory.getWebServer();
        server.start();

        KeyStore trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("server", certificate);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        client = SSLContext.getInstance("TLS");
        client.init(null, trust.getTrustManagers(), null);
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void everySuiteOfferedIsFipsApprovedAndKnownToTheJdk() throws Exception {
        List<String> ciphers = Arrays.asList(ssl.getCiphers());
        assertThat(ciphers).isNotEmpty().allMatch(FIPS_APPROVED::contains, "FIPS-approved (SP 800-52 Rev. 2)");
        // A name the JDK does not know would be dropped without a word, leaving fewer suites
        // than the policy says.
        assertThat(Arrays.asList(SSLContext.getDefault().getSupportedSSLParameters().getCipherSuites()))
                .containsAll(ciphers);
        assertThat(ciphers).containsExactly(
                "TLS_AES_256_GCM_SHA384",
                "TLS_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
    }

    @Test
    void tls13AndTls12HandshakeOnApprovedSuites() throws Exception {
        assertThat(handshake("TLSv1.3", "TLS_AES_256_GCM_SHA384")).isEqualTo("TLS_AES_256_GCM_SHA384");
        assertThat(handshake("TLSv1.3", "TLS_AES_128_GCM_SHA256")).isEqualTo("TLS_AES_128_GCM_SHA256");
        assertThat(handshake("TLSv1.2", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"))
                .isEqualTo("TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384");
        assertThat(handshake("TLSv1.2", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"))
                .isEqualTo("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
    }

    @Test
    void aClientOfferingEverythingGetsAnApprovedSuite() throws Exception {
        try (SSLSocket socket = connect()) {
            socket.startHandshake();
            assertThat(socket.getSession().getProtocol()).isEqualTo("TLSv1.3");
            assertThat(FIPS_APPROVED).contains(socket.getSession().getCipherSuite());
        }
    }

    @Test
    void suitesOutsideThePolicyAreRefused() {
        // ChaCha20-Poly1305 is not a FIPS algorithm; CBC and static RSA key exchange are
        // outside this policy.
        for (String[] attempt : List.of(
                new String[] {"TLSv1.3", "TLS_CHACHA20_POLY1305_SHA256"},
                new String[] {"TLSv1.2", "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"},
                new String[] {"TLSv1.2", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"},
                new String[] {"TLSv1.2", "TLS_RSA_WITH_AES_256_GCM_SHA384"})) {
            assertThatThrownBy(() -> handshake(attempt[0], attempt[1]))
                    .as("%s with %s", attempt[0], attempt[1])
                    .isInstanceOf(SSLException.class);
        }
    }

    private static String handshake(String protocol, String suite) throws IOException {
        try (SSLSocket socket = connect()) {
            socket.setEnabledProtocols(new String[] {protocol});
            socket.setEnabledCipherSuites(new String[] {suite});
            socket.startHandshake();
            return socket.getSession().getCipherSuite();
        }
    }

    private static SSLSocket connect() throws IOException {
        SSLSocket socket = (SSLSocket)
                client.getSocketFactory().createSocket(InetAddress.getLoopbackAddress(), server.getPort());
        socket.setSoTimeout(10_000);
        return socket;
    }
}

package com.winllc.certalert.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.winllc.certalert.support.TestCertificates;
import com.winllc.certalert.support.TestConnectProxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Webhooks through an egress proxy: a CONNECT tunnel to the address the guard checked - never
 * to the name, which the proxy would look up again - with TLS to the receiver's name end to end
 * through it, the proxy's credentials when it wants them, and anything but a 2xx from the proxy
 * a failed delivery.
 */
@ExtendWith(OutputCaptureExtension.class)
class WebhookProxyTest {

    private static final String HOST = "hooks.example.test";

    private static HttpsServer receiver;
    private static SSLContext clientTls;
    private static final List<String> received = Collections.synchronizedList(new ArrayList<>());

    private TestConnectProxy proxy;

    @BeforeAll
    static void startReceiver() throws Exception {
        X509Certificate certificate = TestCertificates.certificateOf(TestCertificates.withNames(HOST, HOST));
        char[] password = "changeit".toCharArray();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setKeyEntry("receiver", TestCertificates.sharedKeyPair().getPrivate(), password, new Certificate[] {certificate});
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, password);
        SSLContext serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keyManagers.getKeyManagers(), null, null);
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("receiver", certificate);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, trustManagers.getTrustManagers(), null);

        receiver = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        receiver.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        receiver.createContext("/hook", exchange -> {
            received.add(exchange.getRequestHeaders().getFirst("Host") + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
    }

    @AfterAll
    static void stopReceiver() {
        receiver.stop(0);
    }

    @BeforeEach
    void startProxy() throws Exception {
        received.clear();
        proxy = new TestConnectProxy();
    }

    @AfterEach
    void stopProxy() throws Exception {
        proxy.close();
    }

    private WebhookProperties throughProxy() {
        WebhookProperties properties = new WebhookProperties();
        properties.getProxy().setHost("127.0.0.1");
        properties.getProxy().setPort(proxy.port());
        return properties;
    }

    private URI hook() {
        return URI.create("https://" + HOST + ":" + receiver.getAddress().getPort() + "/hook");
    }

    private WebhookTransport.Response post(WebhookProperties properties) throws Exception {
        return new WebhookTransport(properties, clientTls.getSocketFactory())
                .post(hook(), InetAddress.getLoopbackAddress(), Map.of("Content-Type", "application/json"),
                        "{\"x\":1}".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aDeliveryGoesThroughTheProxyToTheCheckedAddress() throws Exception {
        assertThat(post(throughProxy()).status()).isEqualTo(204);

        // The tunnel is to the address the guard checked, never to the name.
        assertThat(proxy.connects()).singleElement().satisfies(connect -> {
            assertThat(connect.requestLine())
                    .isEqualTo("CONNECT 127.0.0.1:" + receiver.getAddress().getPort() + " HTTP/1.1");
            assertThat(connect.requestLine()).doesNotContain(HOST);
            assertThat(connect.authorization()).isNull();
        });
        // And TLS and the request inside it are the receiver's, by name.
        assertThat(received).containsExactly(HOST + ":" + receiver.getAddress().getPort() + " {\"x\":1}");
    }

    @Test
    void theReceiversCertificateIsStillCheckedThroughTheTunnel() {
        // The default trust store does not trust the receiver: refused, though the proxy said yes.
        assertThatThrownBy(() -> new WebhookTransport(throughProxy())
                        .post(hook(), InetAddress.getLoopbackAddress(), Map.of(), new byte[] {'{', '}'}))
                .isInstanceOf(javax.net.ssl.SSLException.class);
        assertThat(proxy.connects()).hasSize(1);
        assertThat(received).isEmpty();
    }

    @Test
    void aProxyThatWantsCredentialsGetsThemFromTheFile(@TempDir Path dir) throws Exception {
        String expected = "Basic " + Base64.getEncoder().encodeToString("certalert:s3cret pass".getBytes(StandardCharsets.UTF_8));
        proxy.requireAuthorization(expected);

        // None configured: the proxy's 407 is a failed delivery that says why.
        assertThatThrownBy(() -> post(throughProxy()))
                .isInstanceOf(WebhookTransport.ProxyRefusedException.class)
                .hasMessageContaining("407");
        assertThat(received).isEmpty();

        Path file = dir.resolve("proxy-password");
        Files.writeString(file, "s3cret pass\n");
        WebhookProperties properties = throughProxy();
        properties.getProxy().setUsername("certalert");
        properties.getProxy().setPasswordFile(file.toString());
        assertThat(post(properties).status()).isEqualTo(204);
        assertThat(proxy.connects().getLast().authorization()).isEqualTo(expected);

        // Read at each connection: a rotated password is used without a restart.
        Files.writeString(file, "rotated");
        assertThatThrownBy(() -> post(properties)).hasMessageContaining("407");
        proxy.requireAuthorization("Basic " + Base64.getEncoder().encodeToString("certalert:rotated".getBytes(StandardCharsets.UTF_8)));
        assertThat(post(properties).status()).isEqualTo(204);
    }

    @Test
    void aPasswordFromTheEnvironmentWorksToo() throws Exception {
        proxy.requireAuthorization("Basic " + Base64.getEncoder().encodeToString("u:p".getBytes(StandardCharsets.UTF_8)));
        WebhookProperties properties = throughProxy();
        properties.getProxy().setUsername("u");
        properties.getProxy().setPassword("p");
        assertThat(post(properties).status()).isEqualTo(204);
    }

    @Test
    void aRedirectOrRefusalFromTheProxyIsAFailedDelivery() {
        proxy.answerWith("HTTP/1.1 302 Found\r\nLocation: https://elsewhere.example/");
        assertThatThrownBy(() -> post(throughProxy()))
                .isInstanceOf(WebhookTransport.ProxyRefusedException.class)
                .hasMessageContaining("answered 302");
        proxy.answerWith("HTTP/1.1 403 Forbidden");
        assertThatThrownBy(() -> post(throughProxy())).hasMessageContaining("answered 403");
        proxy.answerWith("SSH-2.0-OpenSSH");
        assertThatThrownBy(() -> post(throughProxy())).hasMessageContaining("did not answer CONNECT with HTTP");
        assertThat(received).isEmpty();
    }

    @Test
    void anIpv6AddressIsBracketedAndLosesItsZone() throws Exception {
        assertThat(WebhookTransport.authority(InetAddress.getByName("2001:db8::1"), 443)).isEqualTo("[2001:db8:0:0:0:0:0:1]:443");
        assertThat(WebhookTransport.authority(InetAddress.getByName("192.0.2.7"), 8443)).isEqualTo("192.0.2.7:8443");
    }

    @Test
    void aProxySettingThatCannotWorkStopsTheStartAndNeverShowsTheCredentials(CapturedOutput output) {
        WebhookProperties.Proxy noPort = new WebhookProperties.Proxy();
        noPort.setHost("proxy.example.gov");
        assertThatThrownBy(() -> WebhookTransport.checkProxy(noPort)).hasMessageContaining("proxy.port");

        WebhookProperties.Proxy withScheme = new WebhookProperties.Proxy();
        withScheme.setHost("http://proxy.example.gov");
        withScheme.setPort(3128);
        assertThatThrownBy(() -> WebhookTransport.checkProxy(withScheme)).hasMessageContaining("without a scheme");

        WebhookProperties.Proxy both = new WebhookProperties.Proxy();
        both.setHost("proxy.example.gov");
        both.setPort(3128);
        both.setUsername("u");
        both.setPassword("hunter2");
        both.setPasswordFile("/run/secrets/proxy");
        assertThatThrownBy(() -> WebhookTransport.checkProxy(both))
                .hasMessageContaining("not both")
                .hasMessageNotContaining("hunter2");

        WebhookProperties.Proxy noUser = new WebhookProperties.Proxy();
        noUser.setHost("proxy.example.gov");
        noUser.setPort(3128);
        noUser.setPassword("hunter2");
        assertThatThrownBy(() -> WebhookTransport.checkProxy(noUser)).hasMessageContaining("go together");

        WebhookProperties.Proxy missingFile = new WebhookProperties.Proxy();
        missingFile.setHost("proxy.example.gov");
        missingFile.setPort(3128);
        missingFile.setUsername("u");
        missingFile.setPasswordFile("/nonexistent/proxy-password");
        assertThatThrownBy(() -> WebhookTransport.checkProxy(missingFile)).hasMessageContaining("cannot be read");

        WebhookProperties.Proxy credentialsWithoutHost = new WebhookProperties.Proxy();
        credentialsWithoutHost.setUsername("u");
        credentialsWithoutHost.setPassword("hunter2");
        assertThatThrownBy(() -> WebhookTransport.checkProxy(credentialsWithoutHost)).hasMessageContaining("proxy.host");

        WebhookProperties.Proxy good = new WebhookProperties.Proxy();
        good.setHost("proxy.example.gov");
        good.setPort(3128);
        good.setUsername("certalert");
        good.setPassword("hunter2");
        WebhookTransport.checkProxy(good);
        assertThat(good.toString()).isEqualTo("proxy.example.gov:3128");
        assertThat(output.getAll()).contains("Webhooks go through the proxy proxy.example.gov:3128, with credentials")
                .doesNotContain("hunter2");
    }
}

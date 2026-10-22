package com.winllc.certalert.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.winllc.certalert.support.TestCertificates;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * One receiver cannot hold the sender: whatever the delivery is waiting on - a TLS handshake
 * that never comes, a body nobody reads - the watchdog closes it at the delivery timeout,
 * though every single wait is far shorter than that would allow.
 */
class WebhookTransportTest {

    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable closeable : open) {
            closeable.close();
        }
    }

    private static WebhookProperties slowSocketsQuickDeadline() {
        WebhookProperties properties = new WebhookProperties();
        properties.setConnectTimeout(Duration.ofSeconds(5));
        properties.setReadTimeout(Duration.ofSeconds(60));
        properties.setDeliveryTimeout(Duration.ofSeconds(1));
        return properties;
    }

    /** Accepts, then says nothing and reads nothing - for as long as anybody waits. */
    private int silentServer(ServerSocket server) {
        open.add(server);
        Thread accepting = new Thread(() -> {
            try {
                while (true) {
                    Socket client = server.accept();
                    open.add(client);
                    if (client instanceof SSLSocket tls) {
                        tls.startHandshake();
                    }
                }
            } catch (Exception e) {
                // closed at the end of the test
            }
        });
        accepting.setDaemon(true);
        accepting.start();
        return server.getLocalPort();
    }

    @Test
    void aHandshakeThatNeverComesEndsAtTheDeadline() throws Exception {
        int port = silentServer(new ServerSocket(0, 1, InetAddress.getLoopbackAddress()));
        WebhookTransport transport = new WebhookTransport(slowSocketsQuickDeadline());
        long started = System.nanoTime();
        assertThatThrownBy(() -> transport.post(URI.create("https://hooks.example.test:" + port + "/x"),
                        InetAddress.getLoopbackAddress(), Map.of(), new byte[] {'{', '}'}))
                .isInstanceOf(WebhookTransport.DeliveryTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void aBodyNobodyReadsEndsAtTheDeadline() throws Exception {
        X509Certificate certificate = TestCertificates.certificateOf(
                TestCertificates.withNames("hooks.example.test", "hooks.example.test"));
        char[] password = "changeit".toCharArray();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setKeyEntry("r", TestCertificates.sharedKeyPair().getPrivate(), password, new Certificate[] {certificate});
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, password);
        SSLContext server = SSLContext.getInstance("TLS");
        server.init(keyManagers.getKeyManagers(), null, null);
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("r", certificate);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, trustManagers.getTrustManagers(), null);

        SSLServerSocket socket = (SSLServerSocket) server.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress());
        socket.setReceiveBufferSize(4096);
        int port = silentServer(socket);
        WebhookTransport transport = new WebhookTransport(slowSocketsQuickDeadline(), client.getSocketFactory());
        long started = System.nanoTime();
        // Far more than any socket buffer holds: the write blocks until somebody reads.
        assertThatThrownBy(() -> transport.post(URI.create("https://hooks.example.test:" + port + "/x"),
                        InetAddress.getLoopbackAddress(), Map.of(), new byte[64 * 1024 * 1024]))
                .isInstanceOf(WebhookTransport.DeliveryTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }
}

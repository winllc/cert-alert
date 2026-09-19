package com.winllc.certalert.support;

import java.io.IOException;
import java.net.InetAddress;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * A TLS endpoint that serves one certificate, so the probe can be tested against a real
 * handshake rather than a mock of one.
 *
 * <p>Handshakes and hangs up. Nothing is ever said over the connection: what the probe is
 * for is the certificate, and that arrives before a byte of application data would.
 */
public final class TestTlsServer implements AutoCloseable {

    private static final char[] PASSWORD = "changeit".toCharArray();

    private final SSLServerSocket socket;
    private final Thread accepting;
    private volatile boolean running = true;

    public TestTlsServer(X509Certificate certificate, PrivateKey key) {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("leaf", key, PASSWORD, new Certificate[] {certificate});

            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(store, PASSWORD);

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers.getKeyManagers(), null, null);

            // Port 0: the operating system picks a free one, so tests never collide.
            this.socket = (SSLServerSocket)
                    context.getServerSocketFactory().createServerSocket(0, 1, InetAddress.getLoopbackAddress());
        } catch (java.security.GeneralSecurityException | IOException e) {
            throw new IllegalStateException("Could not start a TLS server for the test", e);
        }
        this.accepting = new Thread(this::accept, "test-tls-server");
        this.accepting.setDaemon(true);
        this.accepting.start();
    }

    public int port() {
        return socket.getLocalPort();
    }

    private void accept() {
        while (running) {
            try (SSLSocket client = (SSLSocket) socket.accept()) {
                client.startHandshake();
            } catch (IOException e) {
                // A closed server socket during shutdown, or a client that hung up mid
                // handshake. Neither is the test's subject.
                if (!running) {
                    return;
                }
            }
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            socket.close();
        } catch (IOException e) {
            // Closing on the way out.
        }
        accepting.interrupt();
    }
}

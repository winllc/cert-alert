package com.winllc.certalert.service;

import com.winllc.certalert.config.ProbeProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Opens a TLS connection and reads the certificate chain the other end presents.
 *
 * <p>What {@code openssl s_client -connect host:port -servername host} does, and for the
 * same reason: the directory says what a server's certificate <em>is</em>, and only the
 * server can say what it is actually serving. A certificate renewed in the directory and
 * never installed looks perfect here and expires in production.
 *
 * <p>The handshake deliberately trusts every chain. This reads certificates, it does not
 * rely on them: an expired, self-signed or otherwise untrusted certificate is exactly what
 * is worth reporting, and a validating trust manager would abort the handshake before it
 * could be read. Nothing is ever sent over these connections and nothing read from them is
 * believed - the certificate is compared against what the directory publishes, which is the
 * only thing here that is trusted.
 */
@Service
public class TlsEndpointProbe {

    private static final Logger log = LoggerFactory.getLogger(TlsEndpointProbe.class);

    private final ProbeProperties properties;
    private final SSLSocketFactory socketFactory;

    public TlsEndpointProbe(ProbeProperties properties) {
        this.properties = properties;
        this.socketFactory = permissiveSocketFactory();
    }

    /**
     * What one endpoint presented.
     *
     * @param chain leaf first, as the server sent it - which may be just the leaf, since a
     *     server that sends no intermediates is itself worth seeing
     */
    public record Handshake(
            String protocol, String cipherSuite, List<X509Certificate> chain, Duration elapsed) {

        public X509Certificate leaf() {
            return chain.getFirst();
        }
    }

    /**
     * Connects, handshakes, and reads.
     *
     * @throws EndpointProbeException if the endpoint is unreachable, speaks no TLS, or
     *     presents nothing
     */
    public Handshake probe(String host, int port) {
        log.debug("Probing {}:{}", host, port);
        long startedNanos = System.nanoTime();
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(host, port), (int) properties.getConnectTimeout().toMillis());
            try (SSLSocket socket = (SSLSocket) socketFactory.createSocket(plain, host, port, true)) {
                socket.setSoTimeout((int) properties.getReadTimeout().toMillis());
                applyServerNameIndication(socket, host);
                socket.startHandshake();
                SSLSession session = socket.getSession();
                return new Handshake(
                        session.getProtocol(),
                        session.getCipherSuite(),
                        chainOf(session, host, port),
                        Duration.ofNanos(System.nanoTime() - startedNanos));
            }
        } catch (IOException e) {
            throw new EndpointProbeException(
                    "Could not read a certificate from %s:%d - %s".formatted(host, port, describe(e)), e);
        }
    }

    private List<X509Certificate> chainOf(SSLSession session, String host, int port) throws IOException {
        List<X509Certificate> chain = new ArrayList<>();
        for (Certificate certificate : session.getPeerCertificates()) {
            if (certificate instanceof X509Certificate x509) {
                chain.add(x509);
            }
        }
        if (chain.isEmpty()) {
            throw new EndpointProbeException("No X.509 certificate presented by %s:%d".formatted(host, port));
        }
        return List.copyOf(chain);
    }

    /**
     * Sends the name being asked for, so a host serving several sites answers with the
     * right certificate rather than its default one. An IP literal is not a valid server
     * name, so it is left off - the same thing {@code s_client} does.
     */
    private void applyServerNameIndication(SSLSocket socket, String host) {
        if (isIpLiteral(host)) {
            return;
        }
        try {
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setServerNames(List.of(new SNIHostName(host)));
            socket.setSSLParameters(parameters);
        } catch (IllegalArgumentException e) {
            log.debug("Skipping SNI for host '{}': {}", host, e.getMessage());
        }
    }

    static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static SSLSocketFactory permissiveSocketFactory() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new ReadOnlyTrustManager()}, null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not initialise the TLS context for probing", e);
        }
    }

    /** Accepts every chain, so that a broken one can be read and reported rather than refused. */
    private static final class ReadOnlyTrustManager implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // Reading only; nothing is trusted and nothing is sent.
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // Reading only; what was presented is judged against the directory instead.
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}

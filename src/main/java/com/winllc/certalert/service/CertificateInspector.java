package com.winllc.certalert.service;

import com.winllc.certalert.config.CertAlertProperties;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Opens a TLS connection to an endpoint and reads the leaf certificate it presents.
 *
 * <p>The handshake deliberately uses a trust manager that accepts every chain. This is a
 * monitoring tool, not a security boundary: an expired or self-signed certificate is
 * exactly what we need to report on, and a validating trust manager would abort the
 * handshake before we could read it. Nothing is ever sent over these connections, and the
 * certificate's validity is evaluated by {@link CertificateStatusEvaluator} instead.
 */
@Service
public class CertificateInspector {

    private static final Logger log = LoggerFactory.getLogger(CertificateInspector.class);

    private static final int SAN_TYPE_DNS = 2;

    private final CertAlertProperties properties;
    private final SSLSocketFactory socketFactory;

    public CertificateInspector(CertAlertProperties properties) {
        this.properties = properties;
        this.socketFactory = createPermissiveSocketFactory();
    }

    /**
     * Retrieves the leaf certificate presented by {@code hostname:port}.
     *
     * @throws CertificateInspectionException if the endpoint is unreachable or presents no certificate
     */
    public CertificateDetails inspect(String hostname, int port) {
        log.debug("Inspecting certificate at {}:{}", hostname, port);
        try (Socket plainSocket = new Socket()) {
            plainSocket.connect(new InetSocketAddress(hostname, port), (int) properties.getConnectTimeout().toMillis());
            try (SSLSocket sslSocket = (SSLSocket) socketFactory.createSocket(plainSocket, hostname, port, true)) {
                sslSocket.setSoTimeout((int) properties.getReadTimeout().toMillis());
                applyServerNameIndication(sslSocket, hostname);
                sslSocket.startHandshake();
                return toDetails(leafCertificate(sslSocket, hostname, port));
            }
        } catch (IOException e) {
            throw new CertificateInspectionException(
                    "Unable to retrieve certificate from %s:%d - %s".formatted(hostname, port, describe(e)), e);
        }
    }

    private X509Certificate leafCertificate(SSLSocket socket, String hostname, int port) throws IOException {
        java.security.cert.Certificate[] chain = socket.getSession().getPeerCertificates();
        if (chain.length == 0 || !(chain[0] instanceof X509Certificate leaf)) {
            throw new CertificateInspectionException(
                    "No X.509 certificate presented by %s:%d".formatted(hostname, port));
        }
        return leaf;
    }

    private CertificateDetails toDetails(X509Certificate certificate) {
        return new CertificateDetails(
                certificate.getSubjectX500Principal().getName(),
                certificate.getIssuerX500Principal().getName(),
                certificate.getSerialNumber().toString(16),
                certificate.getNotBefore().toInstant(),
                certificate.getNotAfter().toInstant(),
                subjectAlternativeNames(certificate),
                certificate.getSigAlgName());
    }

    private List<String> subjectAlternativeNames(X509Certificate certificate) {
        try {
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            if (names == null) {
                return List.of();
            }
            List<String> dnsNames = new ArrayList<>();
            for (List<?> entry : names) {
                if (entry.size() >= 2 && Integer.valueOf(SAN_TYPE_DNS).equals(entry.get(0))) {
                    dnsNames.add(String.valueOf(entry.get(1)));
                }
            }
            return dnsNames;
        } catch (CertificateParsingException e) {
            log.debug("Could not parse subject alternative names", e);
            return List.of();
        }
    }

    /**
     * Sends SNI so that virtual hosts return the right certificate. IP literals are not
     * valid SNI values, so they are skipped.
     */
    private void applyServerNameIndication(SSLSocket socket, String hostname) {
        if (isIpLiteral(hostname)) {
            return;
        }
        try {
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setServerNames(List.of(new SNIHostName(hostname)));
            socket.setSSLParameters(parameters);
        } catch (IllegalArgumentException e) {
            log.debug("Skipping SNI for host '{}': {}", hostname, e.getMessage());
        }
    }

    static boolean isIpLiteral(String hostname) {
        if (hostname.indexOf(':') >= 0) {
            return true;
        }
        return hostname.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }

    private static SSLSocketFactory createPermissiveSocketFactory() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new InspectionTrustManager()}, null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to initialise TLS context for certificate inspection", e);
        }
    }

    /**
     * Accepts any chain so that expired, self-signed, or otherwise untrusted certificates
     * can still be read and reported on. See the class javadoc for why this is safe here.
     */
    private static final class InspectionTrustManager implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // inspection only - nothing is trusted or acted upon
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // inspection only - validity is evaluated by CertificateStatusEvaluator
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}

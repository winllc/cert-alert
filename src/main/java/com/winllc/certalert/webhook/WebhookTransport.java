package com.winllc.certalert.webhook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Posts one webhook over https to an address that has already been checked.
 *
 * <p>Deliberately not an HTTP client library: every one of those looks the host up again to
 * connect, and a name that pointed somewhere harmless for the check can point at the
 * metadata service for the connection. This opens the socket to the checked address itself,
 * then speaks TLS to it as the host the URL names - SNI and certificate hostname
 * verification both against that name, against the JVM's trust store - and sends one
 * HTTP/1.1 request. Redirects are not followed: a 3xx is a failed delivery. The answer is
 * read only as far as its status line and a few kilobytes.
 */
@Component
public class WebhookTransport {

    /** The most of a receiver's answer that is read, headers included. */
    private static final int MAX_RESPONSE = 16 * 1024;

    private final WebhookProperties properties;
    private final SSLSocketFactory sockets;

    @Autowired
    public WebhookTransport(WebhookProperties properties) {
        this(properties, (SSLSocketFactory) SSLSocketFactory.getDefault());
    }

    public WebhookTransport(WebhookProperties properties, SSLSocketFactory sockets) {
        this.properties = properties;
        this.sockets = sockets;
    }

    /** The receiver's status, and the start of what it said. */
    public record Response(int status, String body) {

        public boolean successful() {
            return status >= 200 && status < 300;
        }
    }

    public Response post(URI uri, InetAddress address, Map<String, String> headers, byte[] body) throws IOException {
        String host = WebhookAddressGuard.hostOf(uri);
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        long deadline = System.nanoTime() + properties.getConnectTimeout().plus(properties.getReadTimeout()).toNanos();
        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(address, port), (int) properties.getConnectTimeout().toMillis());
            raw.setSoTimeout((int) properties.getReadTimeout().toMillis());
            try (SSLSocket tls = (SSLSocket) sockets.createSocket(raw, host, port, true)) {
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                if (!isAddressLiteral(host)) {
                    parameters.setServerNames(List.of(new SNIHostName(host)));
                }
                tls.setSSLParameters(parameters);
                tls.startHandshake();

                OutputStream out = tls.getOutputStream();
                StringBuilder request = new StringBuilder();
                String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
                if (uri.getRawQuery() != null) {
                    path += "?" + uri.getRawQuery();
                }
                request.append("POST ").append(path).append(" HTTP/1.1\r\n");
                request.append("Host: ").append(uri.getHost()).append(port == 443 ? "" : ":" + port).append("\r\n");
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    request.append(clean(header.getKey())).append(": ").append(clean(header.getValue())).append("\r\n");
                }
                request.append("Content-Length: ").append(body.length).append("\r\n");
                request.append("Connection: close\r\n\r\n");
                out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
                out.write(body);
                out.flush();
                return read(tls.getInputStream(), deadline);
            }
        }
    }

    private static Response read(InputStream in, long deadline) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[2048];
        int n;
        while (buffer.size() < MAX_RESPONSE && (n = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, Math.min(n, MAX_RESPONSE - buffer.size()));
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        String text = buffer.toString(StandardCharsets.ISO_8859_1);
        int lineEnd = text.indexOf("\r\n");
        String statusLine = lineEnd < 0 ? text : text.substring(0, lineEnd);
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("The receiver did not answer with HTTP");
        }
        int status;
        try {
            status = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("The receiver answered with an unreadable status line");
        }
        int bodyStart = text.indexOf("\r\n\r\n");
        String body = bodyStart < 0 ? "" : text.substring(bodyStart + 4);
        return new Response(status, body.length() > 500 ? body.substring(0, 500) : body);
    }

    /** No line breaks into a header, whatever a value holds. */
    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]", " ");
    }

    private static boolean isAddressLiteral(String host) {
        return host.contains(":") || host.matches("[0-9.]+");
    }
}

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>With an egress proxy configured ({@link WebhookProperties.Proxy}) the socket goes to the
 * proxy instead, which is asked with {@code CONNECT} for a tunnel to the checked address - the
 * address literal, never the name, so the proxy has nothing to look up again - and TLS is
 * spoken through the tunnel exactly as it is directly. Anything but a 2xx answer to the
 * {@code CONNECT}, a redirect included, is a failed delivery.
 */
@Component
public class WebhookTransport {

    private static final Logger log = LoggerFactory.getLogger(WebhookTransport.class);

    /** The most of a receiver's answer that is read, headers included. */
    private static final int MAX_RESPONSE = 16 * 1024;

    /** The most of a proxy's answer to {@code CONNECT} that is read. */
    private static final int MAX_PROXY_ANSWER = 8 * 1024;

    private final WebhookProperties properties;
    private final SSLSocketFactory sockets;

    @Autowired
    public WebhookTransport(WebhookProperties properties) {
        this(properties, (SSLSocketFactory) SSLSocketFactory.getDefault());
    }

    public WebhookTransport(WebhookProperties properties, SSLSocketFactory sockets) {
        this.properties = properties;
        this.sockets = sockets;
        checkProxy(properties.getProxy());
    }

    /**
     * A proxy setting that cannot work stops the application starting, rather than every
     * delivery failing later. Says which proxy, never the credentials.
     */
    static void checkProxy(WebhookProperties.Proxy proxy) {
        boolean hasUser = proxy.getUsername() != null && !proxy.getUsername().isEmpty();
        boolean hasPassword = proxy.getPassword() != null && !proxy.getPassword().isEmpty();
        boolean hasPasswordFile = proxy.getPasswordFile() != null && !proxy.getPasswordFile().isBlank();
        if (!proxy.isConfigured()) {
            if (hasUser || hasPassword || hasPasswordFile) {
                throw new IllegalStateException("cert-alert.webhooks.proxy credentials are set but "
                        + "cert-alert.webhooks.proxy.host (CERT_ALERT_WEBHOOKS_PROXY_HOST) is not");
            }
            return;
        }
        String host = proxy.getHost().trim();
        if (host.contains("/") || host.contains("@") || host.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException("cert-alert.webhooks.proxy.host must be a host name or address alone, "
                    + "without a scheme, credentials or port (the port is cert-alert.webhooks.proxy.port)");
        }
        if (proxy.getPort() < 1 || proxy.getPort() > 65535) {
            throw new IllegalStateException("cert-alert.webhooks.proxy.port (CERT_ALERT_WEBHOOKS_PROXY_PORT) must be "
                    + "the proxy's port, 1-65535, when cert-alert.webhooks.proxy.host is set");
        }
        if (hasPassword && hasPasswordFile) {
            throw new IllegalStateException(
                    "Set cert-alert.webhooks.proxy.password or cert-alert.webhooks.proxy.password-file, not both");
        }
        if (hasUser != (hasPassword || hasPasswordFile)) {
            throw new IllegalStateException("cert-alert.webhooks.proxy.username and a password "
                    + "(cert-alert.webhooks.proxy.password or .password-file) go together: set both or neither");
        }
        if (hasUser && proxy.getUsername().contains(":")) {
            throw new IllegalStateException("cert-alert.webhooks.proxy.username cannot contain ':' (HTTP Basic)");
        }
        if (hasPasswordFile && !Files.isReadable(Path.of(proxy.getPasswordFile().trim()))) {
            throw new IllegalStateException("cert-alert.webhooks.proxy.password-file "
                    + proxy.getPasswordFile().trim() + " cannot be read");
        }
        log.info("Webhooks go through the proxy {}{}", proxy, hasUser ? ", with credentials" : "");
    }

    /** The receiver's status, and the start of what it said. */
    public record Response(int status, String body) {

        public boolean successful() {
            return status >= 200 && status < 300;
        }
    }

    /** Closes a delivery's socket when its time is up; one daemon thread for every delivery. */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "webhook-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    /** A delivery that ran out of time. */
    public static final class DeliveryTimeoutException extends IOException {
        DeliveryTimeoutException(java.time.Duration limit) {
            super("The receiver took longer than " + limit.toSeconds() + " s (cert-alert.webhooks.delivery-timeout)");
        }
    }

    public Response post(URI uri, InetAddress address, Map<String, String> headers, byte[] body) throws IOException {
        Socket raw = new Socket();
        AtomicBoolean timedOut = new AtomicBoolean();
        java.time.Duration limit = properties.getDeliveryTimeout();
        // The socket timeouts bound each wait; this bounds the whole - a handshake or a write
        // that never finishes, a receiver answering a byte at a time - by closing the socket.
        ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> {
            timedOut.set(true);
            try {
                raw.close();
            } catch (IOException ignored) {
                // closed is closed
            }
        }, limit.toMillis(), TimeUnit.MILLISECONDS);
        try {
            return post(raw, uri, address, headers, body, System.nanoTime() + limit.toNanos());
        } catch (IOException e) {
            if (timedOut.get()) {
                throw new DeliveryTimeoutException(limit);
            }
            throw e;
        } finally {
            watchdog.cancel(false);
            raw.close();
        }
    }

    private Response post(Socket raw, URI uri, InetAddress address, Map<String, String> headers, byte[] body, long deadline)
            throws IOException {
        String host = WebhookAddressGuard.hostOf(uri);
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        WebhookProperties.Proxy proxy = properties.getProxy();
        int connectTimeout = (int) properties.getConnectTimeout().toMillis();
        if (proxy.isConfigured()) {
            raw.connect(new InetSocketAddress(proxy.getHost().trim(), proxy.getPort()), connectTimeout);
            raw.setSoTimeout((int) properties.getReadTimeout().toMillis());
            tunnel(raw, proxy, address, port);
        } else {
            raw.connect(new InetSocketAddress(address, port), connectTimeout);
            raw.setSoTimeout((int) properties.getReadTimeout().toMillis());
        }
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

    /** A proxy that would not open the tunnel. */
    public static final class ProxyRefusedException extends IOException {
        ProxyRefusedException(String message) {
            super(message);
        }
    }

    /**
     * Asks the proxy for a tunnel to the checked address and reads its answer, header by header,
     * no further than the blank line that ends it: whatever follows is the receiver's TLS.
     */
    private static void tunnel(Socket raw, WebhookProperties.Proxy proxy, InetAddress address, int port)
            throws IOException {
        String authority = authority(address, port);
        StringBuilder connect = new StringBuilder();
        connect.append("CONNECT ").append(authority).append(" HTTP/1.1\r\n");
        connect.append("Host: ").append(authority).append("\r\n");
        String credentials = basicCredentials(proxy);
        if (credentials != null) {
            connect.append("Proxy-Authorization: Basic ").append(credentials).append("\r\n");
        }
        connect.append("\r\n");
        OutputStream out = raw.getOutputStream();
        out.write(connect.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String answer = readHead(raw.getInputStream());
        int lineEnd = answer.indexOf("\r\n");
        String[] parts = (lineEnd < 0 ? answer : answer.substring(0, lineEnd)).split(" ", 3);
        int status;
        try {
            if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
                throw new NumberFormatException();
            }
            status = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new ProxyRefusedException("The proxy " + proxy + " did not answer CONNECT with HTTP");
        }
        if (status == 407) {
            throw new ProxyRefusedException("The proxy " + proxy + " wants credentials it was not given or "
                    + "refused them (407; cert-alert.webhooks.proxy.username and password)");
        }
        if (status < 200 || status > 299) {
            throw new ProxyRefusedException("The proxy " + proxy + " answered " + status + " to CONNECT " + authority);
        }
    }

    /** The checked address as a CONNECT target: an address literal, IPv6 in brackets, no zone. */
    static String authority(InetAddress address, int port) {
        String literal = address.getHostAddress();
        int zone = literal.indexOf('%');
        if (zone >= 0) {
            literal = literal.substring(0, zone);
        }
        return (literal.contains(":") ? "[" + literal + "]" : literal) + ":" + port;
    }

    /** Base64 of user:password, the password read now (a file can be rotated); null without credentials. */
    private static String basicCredentials(WebhookProperties.Proxy proxy) throws IOException {
        String user = proxy.getUsername();
        if (user == null || user.isEmpty()) {
            return null;
        }
        String password;
        if (proxy.getPasswordFile() != null && !proxy.getPasswordFile().isBlank()) {
            try {
                password = Files.readString(Path.of(proxy.getPasswordFile().trim()), StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                // The path is the operator's own setting; the content never appears.
                throw new IOException("cert-alert.webhooks.proxy.password-file could not be read");
            }
        } else {
            password = proxy.getPassword() == null ? "" : proxy.getPassword();
        }
        return Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    /** Up to and including the blank line ending a header block, a byte at a time. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (head.size() < MAX_PROXY_ANSWER) {
            int b = in.read();
            if (b == -1) {
                break;
            }
            head.write(b);
            matched = (b == (matched % 2 == 0 ? '\r' : '\n')) ? matched + 1 : (b == '\r' ? 1 : 0);
            if (matched == 4) {
                return head.toString(StandardCharsets.ISO_8859_1);
            }
        }
        throw new ProxyRefusedException("The proxy's answer to CONNECT ended or ran on without a blank line");
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

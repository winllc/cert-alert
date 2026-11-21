package com.winllc.certalert.audit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * RFC 5424 syslog over udp, tcp or tls. Each event is one message:
 * {@code <PRI>1 TIMESTAMP HOST certalert - <action> - <the JSON line>}, its severity mapped
 * from the event's (info 6, notice 5, warning 4, alert 1). Over tcp and tls messages are
 * octet-counted ({@code <length> <message>}, RFC 6587 / RFC 5425); udp sends one datagram
 * each. TLS trusts the JVM's trust store.
 *
 * <p>TCP has no delivery acknowledgement, so a write into a connection the collector has closed
 * succeeds locally and is lost. Before each batch the connection is therefore checked (the
 * collector never sends, so a read that returns end of stream or fails means it is gone) and
 * replaced after {@link #IDLE_RECONNECT_MILLIS} unused. When a connection turns out broken, the
 * batch last written on it is sent again on the next one, so a collector restart can repeat up to
 * one batch (same event {@code id}). A batch that cannot be written within
 * {@link #writeTimeoutMillis} (the collector stopped reading) fails. Even so, tcp and tls are best
 * effort; where no event may be missing use Splunk HEC or the pull API.
 */
public class SyslogForwarder implements AuditForwarder {

    private static final Map<String, Integer> FACILITIES = Map.ofEntries(
            Map.entry("kern", 0), Map.entry("user", 1), Map.entry("mail", 2), Map.entry("daemon", 3),
            Map.entry("auth", 4), Map.entry("syslog", 5), Map.entry("lpr", 6), Map.entry("news", 7),
            Map.entry("uucp", 8), Map.entry("cron", 9), Map.entry("authpriv", 10), Map.entry("ftp", 11),
            Map.entry("local0", 16), Map.entry("local1", 17), Map.entry("local2", 18), Map.entry("local3", 19),
            Map.entry("local4", 20), Map.entry("local5", 21), Map.entry("local6", 22), Map.entry("local7", 23));

    private static final int TIMEOUT_MILLIS = 30_000;

    /** A connection unused this long is replaced before the next batch: a firewall may have dropped it. */
    static final long IDLE_RECONNECT_MILLIS = 30_000;

    private final String host;
    private final int port;
    private final String protocol;
    private final int facility;
    private final String facilityName;
    private final String hostname;
    private final String appName;

    private Socket socket;
    /** The TCP socket under {@link #socket} (the same one without tls). */
    private Socket raw;
    private long lastUsed;
    /** The batch last written on the current connection, and one written on a connection that then broke. */
    private List<StoredEvent> lastBatch = List.of();
    private List<StoredEvent> resend = List.of();
    /** How long one batch may take to write before the connection is given up. */
    long writeTimeoutMillis = TIMEOUT_MILLIS;

    public SyslogForwarder(EnsAuditProperties.Syslog settings, String hostname, String appName) {
        this.host = settings.getHost();
        this.port = settings.effectivePort();
        this.protocol = settings.getProtocol();
        if (!List.of("udp", "tcp", "tls").contains(protocol)) {
            throw new IllegalArgumentException("ENS_AUDIT_SYSLOG_PROTOCOL must be udp, tcp or tls, not " + protocol);
        }
        Integer code = FACILITIES.get(settings.getFacility());
        if (code == null) {
            throw new IllegalArgumentException("Unknown ENS_AUDIT_SYSLOG_FACILITY " + settings.getFacility());
        }
        this.facility = code;
        this.facilityName = settings.getFacility();
        this.hostname = hostname == null || hostname.isBlank() ? "-" : hostname.replace(' ', '_');
        this.appName = appName;
    }

    @Override
    public String name() {
        return "syslog";
    }

    @Override
    public String describe() {
        return protocol + "://" + host + ":" + port + " (facility " + facilityName + ")";
    }

    /** One RFC 5424 message, unframed. */
    String message(StoredEvent event) {
        int severity = AuditRecord.Severity.of(event.severity()).syslog();
        String msgId = event.action() == null || event.action().isBlank() ? "-" : printable(event.action(), 32);
        return "<" + (facility * 8 + severity) + ">1 " + AuditFormat.time(event.time()) + " "
                + printable(hostname, 255) + " " + printable(appName, 48) + " - " + msgId + " - " + event.line();
    }

    @Override
    public synchronized void send(List<StoredEvent> events) throws IOException {
        if (events.isEmpty()) {
            return;
        }
        if ("udp".equals(protocol)) {
            try (DatagramSocket datagrams = new DatagramSocket()) {
                InetAddress address = InetAddress.getByName(host);
                for (StoredEvent event : events) {
                    byte[] bytes = message(event).getBytes(StandardCharsets.UTF_8);
                    datagrams.send(new DatagramPacket(bytes, bytes.length, address, port));
                }
            }
            return;
        }
        try {
            OutputStream out = connection().getOutputStream();
            long first = events.getFirst().seq();
            ByteArrayOutputStream batch = new ByteArrayOutputStream();
            for (StoredEvent event : resend) {
                if (event.seq() < first) {
                    frame(batch, event);
                }
            }
            for (StoredEvent event : events) {
                frame(batch, event);
            }
            write(out, batch.toByteArray());
        } catch (IOException e) {
            broken();
            throw e;
        }
        resend = List.of();
        lastBatch = List.copyOf(events);
        lastUsed = System.nanoTime();
    }

    /** Octet counting: the message's length in bytes, a space, the message. */
    private void frame(ByteArrayOutputStream batch, StoredEvent event) throws IOException {
        byte[] bytes = message(event).getBytes(StandardCharsets.UTF_8);
        batch.write((bytes.length + " ").getBytes(StandardCharsets.US_ASCII));
        batch.write(bytes);
    }

    /** Writes and flushes, closing the TCP socket when that takes longer than {@link #writeTimeoutMillis}. */
    private void write(OutputStream out, byte[] bytes) throws IOException {
        Socket s = raw;
        AtomicBoolean expired = new AtomicBoolean();
        CompletableFuture<Void> deadline = CompletableFuture.runAsync(() -> {
            expired.set(true);
            closeQuietly(s);
        }, CompletableFuture.delayedExecutor(writeTimeoutMillis, TimeUnit.MILLISECONDS));
        try {
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            if (expired.get()) {
                throw new SocketTimeoutException("the syslog collector took no data for " + writeTimeoutMillis + " ms");
            }
            throw e;
        } finally {
            deadline.cancel(false);
        }
    }

    /**
     * Whether the collector has closed the connection. It never sends anything, so a read that
     * times out at once means the connection is open; end of stream or an error means it is gone.
     */
    static boolean peerClosed(Socket s) {
        try {
            int timeout = s.getSoTimeout();
            s.setSoTimeout(1);
            try {
                byte[] discard = new byte[512];
                for (int i = 0; i < 16; i++) {
                    if (s.getInputStream().read(discard) < 0) {
                        return true;
                    }
                }
                return false; // it is sending something, so it is there
            } finally {
                s.setSoTimeout(timeout);
            }
        } catch (SocketTimeoutException e) {
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    private Socket connection() throws IOException {
        if (socket != null) {
            if (!socket.isConnected() || socket.isClosed() || peerClosed(socket)) {
                broken();
            } else if (System.nanoTime() - lastUsed > TimeUnit.MILLISECONDS.toNanos(IDLE_RECONNECT_MILLIS)) {
                close();
                lastBatch = List.of();
            } else {
                return socket;
            }
        }
        Socket plain = new Socket();
        raw = plain;
        plain.connect(new InetSocketAddress(host, port), TIMEOUT_MILLIS);
        plain.setSoTimeout(TIMEOUT_MILLIS);
        plain.setKeepAlive(true);
        Socket fresh = plain;
        if ("tls".equals(protocol)) {
            SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(plain, host, port, true);
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            tls.startHandshake();
            fresh = tls;
        }
        socket = fresh;
        return fresh;
    }

    /** The connection failed or the collector closed it: the last batch written on it may not have arrived. */
    private void broken() {
        if (!lastBatch.isEmpty()) {
            resend = lastBatch;
        }
        lastBatch = List.of();
        close();
    }

    @Override
    public synchronized void close() {
        closeQuietly(socket);
        closeQuietly(raw);
        socket = null;
        raw = null;
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    /** RFC 5424 header fields are printable US-ASCII without spaces. */
    private static String printable(String value, int max) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c > 32 && c < 127) {
                out.append(c);
            }
            if (out.length() == max) {
                break;
            }
        }
        return out.isEmpty() ? "-" : out.toString();
    }
}

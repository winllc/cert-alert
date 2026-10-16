package com.winllc.certalert.audit;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * RFC 5424 syslog over udp, tcp or tls. Each event is one message:
 * {@code <PRI>1 TIMESTAMP HOST certalert - <action> - <the JSON line>}, its severity mapped
 * from the event's (info 6, notice 5, warning 4, alert 1). Over tcp and tls messages are
 * octet-counted ({@code <length> <message>}, RFC 6587 / RFC 5425); udp sends one datagram
 * each. TLS trusts the JVM's trust store.
 */
public class SyslogForwarder implements AuditForwarder {

    private static final Map<String, Integer> FACILITIES = Map.ofEntries(
            Map.entry("kern", 0), Map.entry("user", 1), Map.entry("mail", 2), Map.entry("daemon", 3),
            Map.entry("auth", 4), Map.entry("syslog", 5), Map.entry("lpr", 6), Map.entry("news", 7),
            Map.entry("uucp", 8), Map.entry("cron", 9), Map.entry("authpriv", 10), Map.entry("ftp", 11),
            Map.entry("local0", 16), Map.entry("local1", 17), Map.entry("local2", 18), Map.entry("local3", 19),
            Map.entry("local4", 20), Map.entry("local5", 21), Map.entry("local6", 22), Map.entry("local7", 23));

    private static final int TIMEOUT_MILLIS = 30_000;

    private final String host;
    private final int port;
    private final String protocol;
    private final int facility;
    private final String facilityName;
    private final String hostname;
    private final String appName;

    private Socket socket;

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
            for (StoredEvent event : events) {
                byte[] bytes = message(event).getBytes(StandardCharsets.UTF_8);
                out.write((bytes.length + " ").getBytes(StandardCharsets.US_ASCII));
                out.write(bytes);
            }
            out.flush();
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    private Socket connection() throws IOException {
        if (socket != null && socket.isConnected() && !socket.isClosed()) {
            return socket;
        }
        Socket plain = new Socket();
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

    @Override
    public synchronized void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing anyway
            }
            socket = null;
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

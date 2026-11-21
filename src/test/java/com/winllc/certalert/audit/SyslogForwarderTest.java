package com.winllc.certalert.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

/** Syslog over tcp against a local listener that goes away or stops reading, as a collector does. */
class SyslogForwarderTest {

    @Test
    void theBatchAfterTheCollectorClosedTheConnectionGoesOutOnANewOne() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(5000);
            SyslogForwarder forwarder = forwarder(listener.getLocalPort());
            try {
                forwarder.send(List.of(event(1, "one"), event(2, "two")));
                try (Socket first = listener.accept()) {
                    first.setSoTimeout(5000);
                    assertThat(lines(first.getInputStream(), 2)).containsExactly("one", "two");
                } // the collector restarts: it closes the connection
                Thread.sleep(100);

                forwarder.send(List.of(event(3, "three")));
                try (Socket second = listener.accept()) {
                    second.setSoTimeout(5000);
                    // the last batch on the closed connection is sent again: it may not have arrived
                    assertThat(lines(second.getInputStream(), 3)).containsExactly("one", "two", "three");
                }
            } finally {
                forwarder.close();
            }
        }
    }

    @Test
    void aCollectorThatStopsReadingFailsTheBatchInsteadOfBlocking() throws Exception {
        try (ServerSocket listener = new ServerSocket()) {
            listener.setReceiveBufferSize(4096);
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 5);
            SyslogForwarder forwarder = forwarder(listener.getLocalPort());
            forwarder.writeTimeoutMillis = 500;
            String big = "x".repeat(64 * 1024);
            List<StoredEvent> batch = LongStream.rangeClosed(1, 200).mapToObj(i -> event(i, big)).toList();
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(20), () ->
                        assertThatThrownBy(() -> forwarder.send(batch)).isInstanceOf(SocketTimeoutException.class));
            } finally {
                forwarder.close();
            }
        }
    }

    private static SyslogForwarder forwarder(int port) {
        EnsAuditProperties.Syslog settings = new EnsAuditProperties.Syslog();
        settings.setHost("127.0.0.1");
        settings.setPort(port);
        settings.setProtocol("tcp");
        settings.setFacility("local0");
        return new SyslogForwarder(settings, "host-1", "certalert");
    }

    private static StoredEvent event(long seq, String line) {
        return new StoredEvent(seq, Instant.parse("2026-10-16T02:10:11.123Z"), "auth.login", "info", line);
    }

    /** The JSON lines (the last field) of the next count octet-counted messages. */
    private static List<String> lines(InputStream in, int count) throws IOException {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            StringBuilder length = new StringBuilder();
            int c;
            while ((c = in.read()) != ' ') {
                assertThat(c).as("frame length digit").isBetween((int) '0', (int) '9');
                length.append((char) c);
            }
            String message = new String(in.readNBytes(Integer.parseInt(length.toString())), StandardCharsets.UTF_8);
            lines.add(message.substring(message.lastIndexOf(" - ") + 3));
        }
        return lines;
    }
}

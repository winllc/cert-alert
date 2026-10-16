package com.winllc.certalert.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.winllc.certalert.repository.AuditDeliveryRepository;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Splunk HEC against a stub HTTP server, and syslog against a local TCP listener: what goes
 * on the wire, retry after a failure, and the cursor that makes delivery resumable.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditForwarderTest {

    @Autowired
    private AuditDeliveryService delivery;

    @Autowired
    private AuditDeliveryRepository cursors;

    @Autowired
    private AuditLog audit;

    @Autowired
    private AuditStore store;

    @Autowired
    private EnsAuditProperties properties;

    @Autowired
    private ObjectMapper json;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        delivery.unregister("splunk");
        cursors.deleteAll();
        properties.setFailureAlertAfter(Duration.ofMinutes(15));
        if (server != null) {
            server.stop(0);
        }
    }

    private record Received(String authorization, String body) {}

    @Test
    void splunkHecGetsBatchesInItsOwnShapeRetriesAndPersistsTheCursor() throws Exception {
        List<Received> received = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/services/collector/event", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new Received(exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(body, StandardCharsets.UTF_8)));
            // The first attempt fails, as a Splunk being restarted would.
            int status = calls.getAndIncrement() == 0 ? 503 : 200;
            byte[] answer = (status == 200 ? "{\"text\":\"Success\",\"code\":0}" : "{\"text\":\"busy\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();

        // Start the cursor at the current head, as a newly enabled output that only wants
        // what happens from now would be after catching up.
        long head = store.headSeq();
        cursors.save(new AuditDelivery("splunk", head));
        properties.setFailureAlertAfter(Duration.ZERO);

        EnsAuditProperties.Splunk settings = new EnsAuditProperties.Splunk();
        settings.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        settings.setToken("test-hec-token");
        settings.setIndex("security");
        delivery.register(new SplunkHecForwarder(settings, "host-1", "certalert"));

        audit.record(AuditRecord.of(AuditRecord.Category.SYSTEM, "test.hec").actor(AuditRecord.Actor.system("t")));
        audit.record(AuditRecord.of(AuditRecord.Category.SYSTEM, "test.hec").actor(AuditRecord.Actor.system("t")));

        // Refused: nothing moves, and the error is on the cursor - and with the alert
        // threshold at zero, the failure is recorded as audit.delivery-failed.
        assertThatThrownBy(() -> delivery.deliverOnce("splunk")).hasMessageContaining("503");
        AuditDelivery failed = cursors.findById("splunk").orElseThrow();
        assertThat(failed.getLastSeq()).isEqualTo(head);
        assertThat(failed.getLastError()).contains("503");
        assertThat(failed.getFailingSince()).isNotNull();
        assertThat(store.after(head + 2, 10)).extracting(StoredEvent::action).containsExactly("audit.delivery-failed");

        // Retried: the same events, and then the alert itself, are delivered.
        int delivered = 0;
        while (true) {
            int batch = delivery.deliverOnce("splunk");
            if (batch == 0) {
                break;
            }
            delivered += batch;
        }
        assertThat(delivered).isGreaterThanOrEqualTo(3);
        AuditDelivery cursor = cursors.findById("splunk").orElseThrow();
        assertThat(cursor.getLastSeq()).isEqualTo(store.headSeq());
        assertThat(cursor.getFailingSince()).isNull();
        assertThat(cursor.getDeliveredAt()).isNotNull();
        // ... and the recovery is itself recorded.
        assertThat(store.after(head + 3, 1).getFirst().action()).isEqualTo("audit.delivery-recovered");

        assertThat(received).hasSizeGreaterThanOrEqualTo(2);
        assertThat(received).allSatisfy(r -> assertThat(r.authorization()).isEqualTo("Splunk test-hec-token"));
        // The retry resends what failed, from the cursor, and carries on from there.
        assertThat(received.get(1).body()).startsWith(received.get(0).body());

        List<JsonNode> events = new ArrayList<>();
        try (var values = json.readerFor(JsonNode.class).<JsonNode>readValues(received.get(1).body())) {
            values.forEachRemaining(events::add);
        }
        assertThat(events).hasSizeGreaterThanOrEqualTo(3);
        JsonNode first = events.getFirst();
        assertThat(first.properties().stream().map(java.util.Map.Entry::getKey).toList())
                .containsExactly("time", "host", "source", "sourcetype", "index", "event");
        assertThat(first.get("time").isNumber()).isTrue();
        assertThat(received.get(1).body()).matches("^\\{\"time\":\\d+\\.\\d{3},.*");
        assertThat(first.get("host").asString()).isEqualTo("host-1");
        assertThat(first.get("source").asString()).isEqualTo("certalert");
        assertThat(first.get("sourcetype").asString()).isEqualTo("ens:audit");
        assertThat(first.get("index").asString()).isEqualTo("security");
        assertThat(first.get("event").get("schema").asString()).isEqualTo("ens-audit/1");
        assertThat(first.get("event").get("seq").asLong()).isEqualTo(head + 1);
        assertThat(first.get("event").get("action").asString()).isEqualTo("test.hec");

        // The cursor is in the database: a restarted delivery service resumes from it.
        assertThat(delivery.deliverOnce("splunk")).isZero();
        assertThat(delivery.statuses()).anySatisfy(status -> {
            assertThat(status.output()).isEqualTo("splunk");
            assertThat(status.enabled()).isTrue();
            assertThat(status.backlog()).isZero();
        });
    }

    @Test
    void syslogOverTcpIsRfc5424WithOctetCounting() throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            EnsAuditProperties.Syslog settings = new EnsAuditProperties.Syslog();
            settings.setHost("127.0.0.1");
            settings.setPort(listener.getLocalPort());
            settings.setProtocol("tcp");
            settings.setFacility("local0");
            SyslogForwarder forwarder = new SyslogForwarder(settings, "host-1", "certalert");

            String lineA = "{\"schema\":\"ens-audit/1\",\"seq\":1,\"note\":\"café\"}";
            String lineB = "{\"schema\":\"ens-audit/1\",\"seq\":2}";
            Instant at = Instant.parse("2026-10-16T02:10:11.123Z");
            forwarder.send(List.of(
                    new StoredEvent(1, at, "auth.login", "warning", lineA),
                    new StoredEvent(2, at, "settings.changed", "notice", lineB)));

            try (Socket accepted = listener.accept()) {
                accepted.setSoTimeout(5000);
                InputStream in = new BufferedInputStream(accepted.getInputStream());
                String first = frame(in);
                String second = frame(in);
                assertThat(first).isEqualTo("<132>1 2026-10-16T02:10:11.123Z host-1 certalert - auth.login - " + lineA);
                assertThat(second).isEqualTo("<133>1 2026-10-16T02:10:11.123Z host-1 certalert - settings.changed - " + lineB);
            } finally {
                forwarder.close();
            }
        }
    }

    @Test
    void syslogDefaultsAndSeverities() {
        EnsAuditProperties.Syslog settings = new EnsAuditProperties.Syslog();
        assertThat(settings.effectivePort()).isEqualTo(514);
        settings.setProtocol("tls");
        assertThat(settings.effectivePort()).isEqualTo(6514);
        settings.setHost("x");
        SyslogForwarder forwarder = new SyslogForwarder(settings, "h", "certalert");
        Instant at = Instant.EPOCH;
        assertThat(forwarder.message(new StoredEvent(1, at, "a", "info", "{}"))).startsWith("<134>1 ");
        assertThat(forwarder.message(new StoredEvent(1, at, "a", "alert", "{}"))).startsWith("<129>1 ");
    }

    /** One octet-counted frame: the length in ASCII, a space, and that many bytes. */
    private static String frame(InputStream in) throws Exception {
        StringBuilder length = new StringBuilder();
        int c;
        while ((c = in.read()) != ' ') {
            assertThat(c).as("frame length digit").isBetween((int) '0', (int) '9');
            length.append((char) c);
        }
        byte[] message = in.readNBytes(Integer.parseInt(length.toString()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(message);
        return out.toString(StandardCharsets.UTF_8);
    }
}

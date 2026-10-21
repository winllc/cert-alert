package com.winllc.certalert.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.winllc.certalert.alert.CertificateAlert;
import com.winllc.certalert.audit.JobAudit;
import com.winllc.certalert.cluster.JobLocks;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.Severity;
import com.winllc.certalert.support.TestCertificates;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Webhooks delivered for real: over TLS to a receiver that checks the signature, retried after
 * a failure, logged; and posted to the address the guard checked rather than to a second
 * lookup of the name - which is what defeats DNS rebinding.
 *
 * <p>The receiver listens on loopback, which the real guard refuses, so the outbox here is
 * given a guard that answers {@code hooks.example.test} with 127.0.0.1 - standing in for DNS,
 * not for the checks, which {@link WebhookAddressGuardTest} covers.
 */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class WebhookDeliveryTest {

    private static final String HOST = "hooks.example.test";

    private static HttpsServer receiver;
    private static SSLContext clientTls;
    private static final List<Received> received = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger answer = new AtomicInteger(200);

    record Received(Map<String, List<String>> headers, String body) {}

    @Autowired
    private WebhookEndpointRepository endpoints;

    @Autowired
    private WebhookDeliveryRepository deliveries;

    @Autowired
    private WebhookPublisher publisher;

    @Autowired
    private WebhookSecrets secrets;

    @Autowired
    private WebhookProperties properties;

    @Autowired
    private WebhookNotifications notifications;

    @Autowired
    private JobLocks locks;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationEventPublisher events;

    private final List<Long> created = new ArrayList<>();

    @BeforeAll
    static void startReceiver() throws Exception {
        X509Certificate certificate = TestCertificates.certificateOf(TestCertificates.withNames(HOST, HOST));
        char[] password = "changeit".toCharArray();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setKeyEntry("receiver", TestCertificates.sharedKeyPair().getPrivate(), password, new Certificate[] {certificate});
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, password);
        SSLContext serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keyManagers.getKeyManagers(), null, null);

        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("receiver", certificate);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trust);
        clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, trustManagers.getTrustManagers(), null);

        receiver = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        receiver.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        receiver.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(exchange.getRequestHeaders(), body));
            exchange.sendResponseHeaders(answer.get(), -1);
            exchange.close();
        });
        receiver.start();
    }

    @AfterAll
    static void stopReceiver() {
        receiver.stop(0);
    }

    @BeforeEach
    void reset() {
        received.clear();
        answer.set(200);
    }

    @AfterEach
    void removeEndpoints() {
        created.forEach(id -> endpoints.findById(id).ifPresent(endpoints::delete));
        notifications.endpointsChanged();
    }

    /** An outbox whose guard answers for the receiver's name, and whose TLS trusts its certificate. */
    private WebhookOutbox outbox(WebhookAddressGuard guard, WebhookTransport transport) {
        return new WebhookOutbox(deliveries, endpoints, guard, transport, secrets, properties, locks, clock, transactions);
    }

    private WebhookAddressGuard resolvingTo(String address) {
        return new WebhookAddressGuard(properties) {
            @Override
            public InetAddress resolve(URI uri) throws IOException {
                return InetAddress.getByName(address);
            }
        };
    }

    private record Endpoint(WebhookEndpoint endpoint, String secret) {}

    private Endpoint endpoint(WebhookEndpoint.Format format, WebhookEvent... events) {
        WebhookEndpoint endpoint = new WebhookEndpoint("test receiver", secrets.store("https://" + HOST + ":"
                + receiver.getAddress().getPort() + "/hook?source=certalert"), format, EnumSet.of(events[0], events), "test", Instant.now());
        String secret = secrets.generate();
        endpoint.secret(secrets.store(secret), secret.substring(0, 12), Instant.now());
        endpoint = endpoints.save(endpoint);
        created.add(endpoint.getId());
        notifications.endpointsChanged();
        return new Endpoint(endpoint, secret);
    }

    private List<WebhookDelivery> log(WebhookEndpoint endpoint) {
        return deliveries.findByEndpointIdOrderByCreatedAtDesc(endpoint.getId(), org.springframework.data.domain.PageRequest.of(0, 50));
    }

    @Test
    void aJobResultIsPostedSignedAndTheReceiverCanVerifyIt() {
        Endpoint target = endpoint(WebhookEndpoint.Format.JSON, WebhookEvent.JOB_COMPLETED, WebhookEvent.JOB_FAILED);

        events.publishEvent(new JobAudit.JobFinished("user sweep", true, "schedule", 1234, Map.of("entriesSeen", 7), null));
        WebhookOutbox outbox = outbox(resolvingTo("127.0.0.1"), new WebhookTransport(properties, clientTls.getSocketFactory()));
        assertThat(outbox.deliverDue()).isGreaterThanOrEqualTo(1);

        assertThat(received).hasSize(1);
        Received post = received.getFirst();
        String signature = post.headers().get("X-certalert-signature").getFirst();
        long now = Instant.now().getEpochSecond();
        assertThat(WebhookSecrets.verify(target.secret(), signature, post.body(), now, 300)).isTrue();
        // A body changed in transit, the wrong secret, or a replay from long ago: all refused.
        assertThat(WebhookSecrets.verify(target.secret(), signature, post.body() + " ", now, 300)).isFalse();
        assertThat(WebhookSecrets.verify(secrets.generate(), signature, post.body(), now, 300)).isFalse();
        assertThat(WebhookSecrets.verify(target.secret(), signature, post.body(), now + 3600, 300)).isFalse();

        assertThat(post.headers().get("X-certalert-event")).containsExactly("job.completed");
        assertThat(post.body()).contains("\"type\":\"job.completed\"", "\"job\":\"user sweep\"", "\"entriesSeen\":7");
        assertThat(log(target.endpoint())).singleElement().satisfies(delivery -> {
            assertThat(delivery.getState()).isEqualTo(WebhookDelivery.State.DELIVERED);
            assertThat(delivery.getLastStatus()).isEqualTo(200);
            assertThat(post.headers().get("X-certalert-delivery")).containsExactly(delivery.getEventId());
        });
    }

    @Test
    void aFailureIsRetriedLaterWithTheSameEventId() {
        Endpoint target = endpoint(WebhookEndpoint.Format.JSON, WebhookEvent.JOB_FAILED);
        publisher.publish(WebhookEvent.JOB_FAILED, Map.of("job", "revocation"), "failed");
        WebhookOutbox outbox = outbox(resolvingTo("127.0.0.1"), new WebhookTransport(properties, clientTls.getSocketFactory()));

        answer.set(503);
        outbox.deliverDue();
        WebhookDelivery first = log(target.endpoint()).getFirst();
        assertThat(first.getState()).isEqualTo(WebhookDelivery.State.PENDING);
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(first.getLastStatus()).isEqualTo(503);
        assertThat(first.getNextAttemptAt()).isAfter(Instant.now());
        // Not due yet: nothing is sent.
        outbox.deliverDue();
        assertThat(received).hasSize(1);

        answer.set(204);
        jdbc.update("UPDATE webhook_delivery SET next_attempt_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), first.getId());
        outbox.deliverDue();
        assertThat(received).hasSize(2);
        assertThat(received.get(0).headers().get("X-certalert-delivery"))
                .isEqualTo(received.get(1).headers().get("X-certalert-delivery"));
        WebhookDelivery second = log(target.endpoint()).getFirst();
        assertThat(second.getState()).isEqualTo(WebhookDelivery.State.DELIVERED);
        assertThat(second.getAttempts()).isEqualTo(2);
    }

    @Test
    void anAddressRefusedAtSendTimeIsNeverConnectedTo() {
        Endpoint target = endpoint(WebhookEndpoint.Format.JSON, WebhookEvent.JOB_COMPLETED);
        publisher.publish(WebhookEvent.JOB_COMPLETED, Map.of("job", "x"), "done");
        AtomicInteger connections = new AtomicInteger();
        WebhookTransport counting = new WebhookTransport(properties, clientTls.getSocketFactory()) {
            @Override
            public Response post(URI uri, InetAddress address, Map<String, String> headers, byte[] body) throws IOException {
                connections.incrementAndGet();
                return super.post(uri, address, headers, body);
            }
        };
        // The name now resolves to the metadata service: the real guard, consulted at the send.
        WebhookAddressGuard rebound = new WebhookAddressGuard(properties,
                host -> new InetAddress[] {InetAddress.getByName("169.254.169.254")});
        outbox(rebound, counting).deliverDue();

        assertThat(connections).hasValue(0);
        assertThat(received).isEmpty();
        assertThat(log(target.endpoint()).getFirst().getLastError()).startsWith("Refused:").contains("169.254.169.254");
    }

    @Test
    void theTransportConnectsToTheCheckedAddressAndVerifiesTheNamedHost() {
        // hooks.example.test resolves nowhere in real DNS; the post still arrives, because the
        // socket goes to the address the guard returned - and TLS still checks the name.
        Endpoint target = endpoint(WebhookEndpoint.Format.SLACK, WebhookEvent.JOB_COMPLETED);
        publisher.publish(WebhookEvent.JOB_COMPLETED, Map.of("job", "x"), "CertAlert job x completed");
        outbox(resolvingTo("127.0.0.1"), new WebhookTransport(properties, clientTls.getSocketFactory())).deliverDue();
        assertThat(received).singleElement().satisfies(post ->
                assertThat(post.body()).isEqualTo("{\"text\":\"CertAlert job x completed\"}"));
        assertThat(log(target.endpoint()).getFirst().getState()).isEqualTo(WebhookDelivery.State.DELIVERED);

        // With the default trust store the receiver's certificate is not trusted: refused, retried.
        publisher.publish(WebhookEvent.JOB_COMPLETED, Map.of("job", "y"), "y");
        received.clear();
        outbox(resolvingTo("127.0.0.1"), new WebhookTransport(properties)).deliverDue();
        assertThat(received).isEmpty();
        assertThat(log(target.endpoint()).getFirst().getState()).isEqualTo(WebhookDelivery.State.PENDING);
    }

    @Test
    void anExpiryAlertGoesToTheEndpointsThatWantIt() {
        Endpoint expiring = endpoint(WebhookEndpoint.Format.TEAMS, WebhookEvent.CERTIFICATE_EXPIRING);
        Endpoint jobsOnly = endpoint(WebhookEndpoint.Format.JSON, WebhookEvent.JOB_COMPLETED);
        assertThat(notifications.isActive()).isTrue();

        notifications.send(new CertificateAlert(OwnerType.SERVER, 7L, "web01", "cn=web01,ou=servers", "ops@example.gov",
                CertificateStatus.EXPIRING_SOON, Severity.WARNING, "CN=web01", "ab".repeat(32), "0A1B",
                Instant.now().plusSeconds(86400 * 5), 5L, Instant.now()));

        assertThat(log(expiring.endpoint())).singleElement().satisfies(delivery -> {
            assertThat(delivery.getEventType()).isEqualTo("certificate.expiring");
            assertThat(delivery.getPayload()).contains("AdaptiveCard", "web01");
        });
        assertThat(log(jobsOnly.endpoint())).isEmpty();
    }

    @Autowired
    private com.winllc.certalert.audit.AuditLog auditLog;

    /**
     * Saved before a key was set, stored as typed; encrypted at the next start, once - and a
     * row changed since it was read is left for the next start rather than overwritten.
     */
    @Test
    void urlsAndSecretsSavedBeforeAKeyAreEncryptedAtStartOnce() {
        Endpoint plain = endpoint(WebhookEndpoint.Format.SLACK, WebhookEvent.JOB_FAILED);
        String url = secrets.revealUrl(plain.endpoint());
        assertThat(plain.endpoint().isUrlEncrypted()).isFalse();

        WebhookProperties keyed = new WebhookProperties();
        keyed.setSecretKey(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        WebhookSecrets sealing = new WebhookSecrets(keyed);
        WebhookSecretEncryption encryption = new WebhookSecretEncryption(endpoints, sealing, locks, auditLog, transactions);

        // A replica that read the row before it was changed: its update finds nothing to do.
        assertThat(jdbc.update("UPDATE webhook_endpoint SET url = url WHERE id = ?", plain.endpoint().getId())).isOne();
        assertThat(endpoints.encryptUrl(plain.endpoint().getId(), "https://stale.example/x", "sealed")).isZero();

        assertThat(encryption.encryptStored()).isGreaterThanOrEqualTo(2);
        WebhookEndpoint stored = endpoints.findById(plain.endpoint().getId()).orElseThrow();
        assertThat(stored.isUrlEncrypted()).isTrue();
        assertThat(stored.isSecretEncrypted()).isTrue();
        assertThat(stored.getStoredUrl()).startsWith("v1:").doesNotContain("hook");
        assertThat(sealing.revealUrl(stored)).isEqualTo(url);
        assertThat(sealing.reveal(stored)).isEqualTo(plain.secret());

        // Again - another start, another replica: nothing left to do, nothing encrypted twice.
        assertThat(encryption.encryptStored()).isZero();
        assertThat(sealing.revealUrl(endpoints.findById(stored.getId()).orElseThrow())).isEqualTo(url);
        // Without a key there is nothing to do at all.
        assertThat(new WebhookSecretEncryption(endpoints, secrets, locks, auditLog, transactions).encryptStored()).isZero();
    }

    /**
     * A replica started without the key leaves an encrypted endpoint's delivery alone - not
     * claimed, no attempt counted, one warning a pass - and a replica with the key sends it.
     */
    @Test
    void aReplicaWithoutTheKeyLeavesEncryptedEndpointsToOneThatHasIt(CapturedOutput output) {
        WebhookProperties keyed = new WebhookProperties();
        keyed.setSecretKey(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        WebhookSecrets sealing = new WebhookSecrets(keyed);
        WebhookEndpoint sealed = new WebhookEndpoint("sealed receiver", sealing.store("https://" + HOST + ":"
                + receiver.getAddress().getPort() + "/hook"), WebhookEndpoint.Format.JSON,
                EnumSet.of(WebhookEvent.JOB_FAILED), "test", Instant.now());
        String secret = sealing.generate();
        sealed.secret(sealing.store(secret), secret.substring(0, 12), Instant.now());
        sealed = endpoints.save(sealed);
        created.add(sealed.getId());
        notifications.endpointsChanged();
        publisher.publish(WebhookEvent.JOB_FAILED, Map.of("job", "revocation"), "failed");
        WebhookDelivery waiting = log(sealed).getFirst();
        assertThat(secrets.encrypts()).isFalse();

        int before = output.getOut().length();
        WebhookOutbox keyless = outbox(resolvingTo("127.0.0.1"), new WebhookTransport(properties, clientTls.getSocketFactory()));
        keyless.deliverDue();
        keyless.deliverDue();

        assertThat(received).isEmpty();
        WebhookDelivery untouched = log(sealed).getFirst();
        assertThat(untouched.getState()).isEqualTo(WebhookDelivery.State.PENDING);
        assertThat(untouched.getAttempts()).isZero();
        assertThat(untouched.getNextAttemptAt()).isEqualTo(waiting.getNextAttemptAt());
        assertThat(untouched.getLastError()).isNull();
        String warning = "are encrypted but this node has no cert-alert.webhooks.secret-key";
        String logged = output.getOut().substring(before);
        // One warning a pass, naming the endpoint: two passes, two warnings.
        assertThat(logged.split(java.util.regex.Pattern.quote(warning), -1)).hasSize(3);
        assertThat(logged).contains(String.valueOf(sealed.getId()));

        WebhookOutbox withKey = new WebhookOutbox(deliveries, endpoints, resolvingTo("127.0.0.1"),
                new WebhookTransport(properties, clientTls.getSocketFactory()), sealing, keyed, locks, clock, transactions);
        assertThat(withKey.deliverDue()).isGreaterThanOrEqualTo(1);
        assertThat(received).singleElement().satisfies(post -> assertThat(WebhookSecrets.verify(secret,
                post.headers().get("X-certalert-signature").getFirst(), post.body(), Instant.now().getEpochSecond(), 300))
                .isTrue());
        WebhookDelivery sent = log(sealed).getFirst();
        assertThat(sent.getState()).isEqualTo(WebhookDelivery.State.DELIVERED);
        assertThat(sent.getAttempts()).isOne();
    }

    /**
     * A receiver that never answers, with a backlog older than everybody else's, costs one try
     * a pass: its other deliveries are put back with it, and the live endpoint's newer ones
     * are all delivered within the run budget.
     */
    @Test
    void aDeadReceiverCostsOneTryAndDoesNotHoldUpTheRest() throws Exception {
        WebhookProperties bounded = new WebhookProperties();
        bounded.setConnectTimeout(java.time.Duration.ofSeconds(1));
        bounded.setReadTimeout(java.time.Duration.ofSeconds(1));
        bounded.setDeliveryTimeout(java.time.Duration.ofSeconds(1));
        bounded.setRunBudget(java.time.Duration.ofSeconds(15));
        try (java.net.ServerSocket blackhole = new java.net.ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            WebhookEndpoint dead = new WebhookEndpoint("blackhole", secrets.store("https://" + HOST + ":"
                    + blackhole.getLocalPort() + "/hook"), WebhookEndpoint.Format.JSON,
                    EnumSet.of(WebhookEvent.JOB_FAILED), "test", Instant.now());
            String deadSecret = secrets.generate();
            dead.secret(secrets.store(deadSecret), deadSecret.substring(0, 12), Instant.now());
            dead = endpoints.save(dead);
            created.add(dead.getId());
            Endpoint live = endpoint(WebhookEndpoint.Format.JSON, WebhookEvent.JOB_COMPLETED);

            Instant anHourAgo = Instant.now().minusSeconds(3600);
            List<WebhookDelivery> backlog = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                backlog.add(new WebhookDelivery(dead.getId(), "dead-" + i + "-" + System.nanoTime(),
                        WebhookEvent.JOB_FAILED, "{\"n\":" + i + "}", anHourAgo.plusMillis(i)));
            }
            for (int i = 0; i < 5; i++) {
                backlog.add(new WebhookDelivery(live.endpoint().getId(), "live-" + i + "-" + System.nanoTime(),
                        WebhookEvent.JOB_COMPLETED, "{\"n\":" + i + "}", Instant.now()));
            }
            deliveries.saveAll(backlog);

            AtomicInteger deadTries = new AtomicInteger();
            int deadPort = blackhole.getLocalPort();
            WebhookTransport counting = new WebhookTransport(bounded, clientTls.getSocketFactory()) {
                @Override
                public Response post(URI uri, InetAddress address, Map<String, String> headers, byte[] body) throws IOException {
                    if (uri.getPort() == deadPort) {
                        deadTries.incrementAndGet();
                    }
                    return super.post(uri, address, headers, body);
                }
            };
            WebhookOutbox outbox = new WebhookOutbox(deliveries, endpoints, resolvingTo("127.0.0.1"), counting, secrets,
                    bounded, locks, clock, transactions);
            long started = System.nanoTime();
            outbox.deliverDue();
            java.time.Duration took = java.time.Duration.ofNanos(System.nanoTime() - started);

            assertThat(took).isLessThan(bounded.getRunBudget());
            assertThat(deadTries).hasValue(1);
            assertThat(received).hasSize(5);
            assertThat(deliveries.findByEndpointIdOrderByCreatedAtDesc(live.endpoint().getId(),
                    org.springframework.data.domain.PageRequest.of(0, 50)))
                    .hasSize(5).allSatisfy(d -> assertThat(d.getState()).isEqualTo(WebhookDelivery.State.DELIVERED));

            List<WebhookDelivery> deadRows = deliveries.findByEndpointIdOrderByCreatedAtDesc(dead.getId(),
                    org.springframework.data.domain.PageRequest.of(0, 500));
            assertThat(deadRows).hasSize(200).allSatisfy(d -> assertThat(d.getState()).isEqualTo(WebhookDelivery.State.PENDING));
            WebhookDelivery tried = deadRows.stream().filter(d -> d.getAttempts() == 1).findFirst().orElseThrow();
            assertThat(tried.getNextAttemptAt()).isAfter(Instant.now());
            // The other 199: not tried, not claimed, and waiting until the same time.
            assertThat(deadRows).filteredOn(d -> d.getAttempts() == 0).hasSize(199)
                    .allSatisfy(d -> assertThat(d.getNextAttemptAt()).isEqualTo(tried.getNextAttemptAt()));

            // The next pass, before that time: nothing more is tried.
            outbox.deliverDue();
            assertThat(deadTries).hasValue(1);
        }
    }

    @Test
    void secretsAreEncryptedWhereAKeyIsConfigured() {
        WebhookProperties keyed = new WebhookProperties();
        keyed.setSecretKey(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        WebhookSecrets sealed = new WebhookSecrets(keyed);
        String secret = sealed.generate();
        WebhookSecrets.Stored stored = sealed.store(secret);
        assertThat(stored.encrypted()).isTrue();
        assertThat(stored.value()).doesNotContain(secret).startsWith("v1:");

        String url = "https://hooks.slack.com/services/T000/B000/XXXXXXXXXXXXXXXXXXXXXXXX";
        WebhookSecrets.Stored storedUrl = sealed.store(url);
        assertThat(storedUrl.encrypted()).isTrue();
        assertThat(storedUrl.value()).doesNotContain("XXXXXXXX").doesNotContain("services");
        WebhookEndpoint endpoint = new WebhookEndpoint("x", storedUrl, WebhookEndpoint.Format.SLACK,
                EnumSet.of(WebhookEvent.JOB_FAILED), "t", Instant.now());
        endpoint.secret(stored, secret.substring(0, 12), Instant.now());
        assertThat(sealed.reveal(endpoint)).isEqualTo(secret);
        assertThat(sealed.revealUrl(endpoint)).isEqualTo(url);
        // What anybody but an administrator sees, and what the audit trail keeps.
        assertThat(WebhookSecrets.redact(url)).isEqualTo("https://hooks.slack.com/[redacted]");
        assertThat(WebhookSecrets.redact("https://hooks.example.org:8443/x?token=abc")).isEqualTo("https://hooks.example.org:8443/[redacted]");
        assertThat(WebhookSecrets.redact("https://hooks.example.org")).isEqualTo("https://hooks.example.org/");
    }
}

package com.winllc.certalert.revocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.winllc.certalert.support.EmbeddedDirectory;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A download of revocation data is bounded in size and time, and goes nowhere a redirect
 * should not take it.
 */
class RevocationFetcherTest {

    private static final int LIMIT = 64 * 1024;

    private HttpServer server;
    /** How much of the oversized body the server managed to hand over before the client hung up. */
    private final AtomicLong written = new AtomicLong();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "test-fetch");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/small.crl", exchange -> {
            byte[] body = new byte[1024];
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        // No Content-Length: chunked, so the size is only known by counting.
        server.createContext("/endless.crl", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[8192];
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 100_000; i++) {
                    out.write(chunk);
                    written.addAndGet(chunk.length);
                }
            } catch (IOException expected) {
                // the client stopped reading, which is the point
            }
        });
        server.createContext("/declared.crl", exchange -> {
            exchange.sendResponseHeaders(200, 50L * 1024 * 1024);
            exchange.close();
        });
        server.createContext("/trickle.crl", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 100; i++) {
                    out.write(1);
                    out.flush();
                    Thread.sleep(100);
                }
            } catch (IOException | InterruptedException expected) {
                // the client gave up
            }
        });
        redirect("/to-file", "file:///etc/passwd");
        redirect("/to-ldap", "ldap://127.0.0.1/cn=CA?certificateRevocationList");
        redirect("/to-metadata", "http://169.254.169.254/latest/meta-data/");
        redirect("/moved", "/small.crl");
        redirect("/loop", "/loop");
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aListWithinTheLimitIsRead() throws IOException {
        assertThat(fetcher(Duration.ofSeconds(10)).get(url("/small.crl"), "*/*")).hasSize(1024);
    }

    @Test
    void aBodyOverTheLimitIsDroppedAsItArrivesRatherThanReadWhole() {
        assertThatThrownBy(() -> fetcher(Duration.ofSeconds(10)).get(url("/endless.crl"), "*/*"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("over the limit");
        // Stopped near the limit, not after the 800 MB the server was prepared to send.
        assertThat(written.get()).isLessThan(64L * 1024 * 1024);
    }

    @Test
    void aDeclaredLengthOverTheLimitIsRefusedBeforeReading() {
        assertThatThrownBy(() -> fetcher(Duration.ofSeconds(10)).get(url("/declared.crl"), "*/*"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("declares");
    }

    @Test
    void theWholeDownloadHasADeadline() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> fetcher(Duration.ofSeconds(1)).get(url("/trickle.crl"), "*/*"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("took longer than");
        // A byte every 100ms never trips a read timeout; the deadline is what stops it.
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aRedirectIsFollowedWithinTheWeb() throws IOException {
        assertThat(fetcher(Duration.ofSeconds(10)).get(url("/moved"), "*/*")).hasSize(1024);
    }

    @Test
    void aRedirectToAnotherSchemeOrToMetadataIsNotFollowed() {
        RevocationFetcher fetcher = fetcher(Duration.ofSeconds(10));
        assertThatThrownBy(() -> fetcher.get(url("/to-file"), "*/*"))
                .isInstanceOf(FetchPolicy.RefusedDestinationException.class);
        assertThatThrownBy(() -> fetcher.get(url("/to-ldap"), "*/*"))
                .isInstanceOf(FetchPolicy.RefusedDestinationException.class);
        assertThatThrownBy(() -> fetcher.get(url("/to-metadata"), "*/*"))
                .isInstanceOf(FetchPolicy.RefusedDestinationException.class);
    }

    @Test
    void redirectsAreNotFollowedForever() {
        assertThatThrownBy(() -> fetcher(Duration.ofSeconds(10)).get(url("/loop"), "*/*"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("redirects");
    }

    @Test
    void loopbackIsRefusedUnlessNamed() {
        RevocationFetcher unnamed = new RevocationFetcher(
                FetchPolicy.of(List.of()), Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(5), LIMIT);
        assertThatThrownBy(() -> unnamed.get(url("/small.crl"), "*/*"))
                .isInstanceOf(FetchPolicy.RefusedDestinationException.class);
    }

    @Test
    void aListPublishedInADirectoryIsReadToTheSameLimit() throws IOException {
        try (EmbeddedDirectory directory = new EmbeddedDirectory()) {
            directory.add(new Entry(
                    "cn=Small CA," + EmbeddedDirectory.BASE_DN,
                    new Attribute("objectClass", "top", "pkiCA"),
                    new Attribute("cn", "Small CA"),
                    new Attribute("certificateRevocationList;binary", new byte[2048])));
            directory.add(new Entry(
                    "cn=Large CA," + EmbeddedDirectory.BASE_DN,
                    new Attribute("objectClass", "top", "pkiCA"),
                    new Attribute("cn", "Large CA"),
                    new Attribute("certificateRevocationList;binary", new byte[4 * LIMIT])));
            RevocationFetcher fetcher = fetcher(Duration.ofSeconds(10));

            assertThat(fetcher.get(directory.url() + "/cn=Small%20CA," + EmbeddedDirectory.BASE_DN
                    + "?certificateRevocationList;binary", "*/*")).hasSize(2048);
            // Bounded by the message size the connection will accept, not read and measured.
            assertThatThrownBy(() -> fetcher.get(directory.url() + "/cn=Large%20CA," + EmbeddedDirectory.BASE_DN
                            + "?certificateRevocationList;binary", "*/*"))
                    .isInstanceOf(IOException.class);
        }
    }

    private RevocationFetcher fetcher(Duration maxTime) {
        return new RevocationFetcher(
                FetchPolicy.of(List.of("localhost", "127.0.0.1")),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                maxTime,
                LIMIT);
    }

    private void redirect(String path, String location) {
        server.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Location", location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }
}

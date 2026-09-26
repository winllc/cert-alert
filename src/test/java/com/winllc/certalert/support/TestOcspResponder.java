package com.winllc.certalert.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An OCSP responder, over HTTP, answering for one authority.
 *
 * <p>A real one, in the sense that matters: it reads the request the client actually sent
 * and signs an answer to the certificate identifier carried in it. Stubbing the answer
 * would test nothing - the part that breaks is whether the question arrives at all, and
 * whether the certificate it names is the one being asked about.
 */
public final class TestOcspResponder implements AutoCloseable {

    private final HttpServer server;
    private final TestCa ca;
    private final Map<BigInteger, Instant> revoked = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger concurrent =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger mostAtOnce =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile long delayMillis;

    public TestOcspResponder(TestCa ca) {
        this.ca = ca;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start a test OCSP responder", e);
        }
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            mostAtOnce.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrent.decrementAndGet();
            }
            byte[] answer;
            try {
                answer = ca.ocspResponse(requestFrom(exchange), Map.copyOf(revoked));
            } catch (RuntimeException | IOException e) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/ocsp-response");
            exchange.sendResponseHeaders(200, answer.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        // A pool, not the default single thread: a responder that can only answer one at a
        // time makes any client look serial.
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(16, runnable -> {
            Thread thread = new Thread(runnable, "test-ocsp");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
    }

    /**
     * The request, however the client chose to send it.
     *
     * <p>Both forms, because the JDK uses the one that is easy to miss: RFC 6960 allows a
     * request of 255 bytes or less to go as a GET with the DER base64-encoded into the path,
     * and a request about a single certificate is always under that. A responder written for
     * POST alone answers nothing and looks like a network fault from the client's side.
     */
    private static byte[] requestFrom(HttpExchange exchange) throws IOException {
        byte[] posted = exchange.getRequestBody().readAllBytes();
        if (posted.length > 0) {
            return posted;
        }
        // The raw path, split before anything is decoded: base64 uses '/' itself, so a
        // decoded path cannot be split on it. And the escapes have to be undone by hand
        // rather than with URLDecoder, which reads '+' as a space - base64 uses that too,
        // and a request mangled that way arrives as "illegal base64 character".
        String raw = exchange.getRequestURI().getRawPath();
        String encoded = raw.substring(raw.lastIndexOf('/') + 1);
        return Base64.getDecoder().decode(unescape(encoded));
    }

    /** Percent-escapes undone, and nothing else touched. */
    private static String unescape(String encoded) {
        StringBuilder out = new StringBuilder(encoded.length());
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c == '%' && i + 2 < encoded.length()) {
                out.append((char) Integer.parseInt(encoded.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Answers this slowly, the way a responder across a network does. */
    public void takesThisLong(long millis) {
        this.delayMillis = millis;
    }

    /** The most requests it was handling at any one moment. */
    public int mostAtOnce() {
        return mostAtOnce.get();
    }

    /** Says this serial is revoked from now on. */
    public void revoke(BigInteger serial, Instant at) {
        revoked.put(serial, at);
    }

    public String url() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/ocsp";
    }

    /** How many questions were asked, which is what says whether it was asked at all. */
    public int requestCount() {
        return requests.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

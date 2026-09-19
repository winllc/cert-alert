package com.winllc.certalert.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serves a few fixed files over HTTP, so a distribution point in a test certificate points
 * somewhere that really answers.
 *
 * <p>Counts the requests, which is how the caching can be tested: a CRL that answers for
 * every certificate an authority issued is only worth anything if it is fetched once.
 */
public final class TestHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();

    public TestHttpServer() {
        try {
            // Port 0: the operating system picks a free one.
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start a test HTTP server", e);
        }
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = files.get(exchange.getRequestURI().getPath());
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/pkix-crl");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    /** Puts a file at a path, replacing whatever was there. */
    public void put(String path, byte[] body) {
        files.put(path, body);
    }

    /** Takes it away, so the next request gets a 404. */
    public void remove(String path) {
        files.remove(path);
    }

    public String url(String path) {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + path;
    }

    public int requestCount() {
        return requests.get();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

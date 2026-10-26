package com.winllc.certalert.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An HTTP forward proxy for the tests: answers {@code CONNECT host:port} by opening a socket
 * there and copying bytes both ways, and records every request line and
 * {@code Proxy-Authorization} it was sent. It can require credentials (407 without them), or
 * answer every {@code CONNECT} with a status of the test's choosing.
 */
public final class TestConnectProxy implements AutoCloseable {

    /** One CONNECT as the proxy saw it. */
    public record Connect(String requestLine, String authorization) {}

    private final ServerSocket server;
    private final List<Connect> connects = Collections.synchronizedList(new ArrayList<>());
    private final List<Socket> open = Collections.synchronizedList(new ArrayList<>());
    private volatile String requiredAuthorization;
    private volatile String fixedAnswer;

    public TestConnectProxy() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread accepting = new Thread(this::accept, "test-connect-proxy");
        accepting.setDaemon(true);
        accepting.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    public List<Connect> connects() {
        return List.copyOf(connects);
    }

    /** Requires {@code Proxy-Authorization: <value>} exactly; null requires nothing. */
    public void requireAuthorization(String value) {
        requiredAuthorization = value;
    }

    /** Answers every CONNECT with this status line and headers instead, e.g. a redirect. */
    public void answerWith(String statusAndHeaders) {
        fixedAnswer = statusAndHeaders;
    }

    private void accept() {
        try {
            while (true) {
                Socket client = server.accept();
                open.add(client);
                Thread handler = new Thread(() -> handle(client), "test-connect-proxy-client");
                handler.setDaemon(true);
                handler.start();
            }
        } catch (IOException closed) {
            // the test is over
        }
    }

    private void handle(Socket client) {
        try {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            String head = readHead(in);
            String[] lines = head.split("\r\n");
            String authorization = null;
            for (String line : lines) {
                if (line.regionMatches(true, 0, "Proxy-Authorization:", 0, 20)) {
                    authorization = line.substring(20).trim();
                }
            }
            connects.add(new Connect(lines[0], authorization));
            if (fixedAnswer != null) {
                out.write((fixedAnswer + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                client.close();
                return;
            }
            String required = requiredAuthorization;
            if (required != null && !required.equals(authorization)) {
                out.write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"test\"\r\nContent-Length: 0\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                client.close();
                return;
            }
            String[] request = lines[0].split(" ");
            if (!"CONNECT".equals(request[0])) {
                out.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                client.close();
                return;
            }
            String target = request[1];
            int colon = target.lastIndexOf(':');
            String host = target.substring(0, colon).replace("[", "").replace("]", "");
            Socket upstream = new Socket(host, Integer.parseInt(target.substring(colon + 1)));
            open.add(upstream);
            out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread back = new Thread(() -> copy(upstream, client), "test-connect-proxy-back");
            back.setDaemon(true);
            back.start();
            copy(client, upstream);
        } catch (IOException e) {
            // a client that went away
        }
    }

    private static void copy(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
        } catch (IOException e) {
            // one side closed
        } finally {
            try {
                to.shutdownOutput();
            } catch (IOException ignored) {
                // closed already
            }
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            byte[] bytes = head.toByteArray();
            int n = bytes.length;
            if (n >= 4 && bytes[n - 4] == '\r' && bytes[n - 3] == '\n' && bytes[n - 2] == '\r' && bytes[n - 1] == '\n') {
                break;
            }
        }
        return head.toString(StandardCharsets.ISO_8859_1);
    }

    @Override
    public void close() throws IOException {
        server.close();
        synchronized (open) {
            for (Socket socket : open) {
                socket.close();
            }
        }
    }
}

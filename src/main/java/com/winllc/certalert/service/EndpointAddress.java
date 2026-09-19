package com.winllc.certalert.service;

import com.winllc.certalert.domain.DirectoryServer;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * Where to probe a server, worked out from what the directory publishes about it.
 *
 * <p>The host is never taken from the request. A caller choosing the port is choosing
 * between the ports of a machine the directory already names; a caller choosing the host
 * would be choosing which machine this application connects to, which is a different thing
 * entirely and not one a page needs.
 *
 * <p>{@code serverURL} first, because a URL is the one attribute that says what the server
 * answers to by name - and its port, where it names one, is the port somebody meant.
 * {@code icServerAddress} is the fallback: an address reaches the machine, though a host
 * serving several sites by name may answer with the wrong certificate, which the name check
 * on the result then reports.
 */
public record EndpointAddress(String host, int port) {

    public static final int LOWEST_PORT = 1;
    public static final int HIGHEST_PORT = 65535;

    /**
     * @param requestedPort the port asked for, or null to use the entry's own
     * @param defaultPort where neither the entry nor the caller names one
     * @throws IllegalArgumentException if the port is not a port
     */
    public static Optional<EndpointAddress> of(DirectoryServer server, Integer requestedPort, int defaultPort) {
        if (requestedPort != null && (requestedPort < LOWEST_PORT || requestedPort > HIGHEST_PORT)) {
            throw new IllegalArgumentException(
                    "Port must be between " + LOWEST_PORT + " and " + HIGHEST_PORT + ", not " + requestedPort);
        }
        Optional<URI> url = parse(server.getServerUrl());
        String host = url.map(URI::getHost)
                .filter(name -> !name.isBlank())
                .orElseGet(() -> blankToNull(server.getIcServerAddress()));
        if (host == null) {
            return Optional.empty();
        }
        int port = requestedPort != null
                ? requestedPort
                : url.map(URI::getPort).filter(named -> named > 0).orElse(defaultPort);
        return Optional.of(new EndpointAddress(host, port));
    }

    /** What the page offers before anybody changes it. */
    public static int defaultPortFor(DirectoryServer server, int fallback) {
        return of(server, null, fallback).map(EndpointAddress::port).orElse(fallback);
    }

    private static Optional<URI> parse(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new URI(url.trim()));
        } catch (URISyntaxException e) {
            // A directory holding something that is not a URL in serverURL is not an error
            // worth failing a probe over; the address attribute may still be good.
            return Optional.empty();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}

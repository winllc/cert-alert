package com.winllc.certalert.service;

/** An endpoint that could not be reached, or that completed no TLS handshake. */
public class EndpointProbeException extends RuntimeException {

    public EndpointProbeException(String message, Throwable cause) {
        super(message, cause);
    }

    public EndpointProbeException(String message) {
        super(message);
    }
}

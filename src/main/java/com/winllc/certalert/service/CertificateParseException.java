package com.winllc.certalert.service;

/** Raised when a directory attribute does not hold a readable X.509 certificate. */
public class CertificateParseException extends RuntimeException {

    public CertificateParseException(String message, Throwable cause) {
        super(message, cause);
    }
}

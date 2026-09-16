package com.winllc.certalert.service;

/** Raised when a TLS endpoint cannot be reached or its certificate cannot be read. */
public class CertificateInspectionException extends RuntimeException {

    public CertificateInspectionException(String message, Throwable cause) {
        super(message, cause);
    }

    public CertificateInspectionException(String message) {
        super(message);
    }
}

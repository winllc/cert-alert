package com.winllc.certalert.service;

/** Raised when a point of contact is already on the server. Rendered as HTTP 409. */
public class ContactAlreadyExistsException extends RuntimeException {

    public ContactAlreadyExistsException(String message) {
        super(message);
    }
}

package com.winllc.certalert.service;

/** Raised when a requested entity does not exist. Rendered as HTTP 404. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException target(Long id) {
        return new ResourceNotFoundException("No certificate target with id " + id);
    }
}

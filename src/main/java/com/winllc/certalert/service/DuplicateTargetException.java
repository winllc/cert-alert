package com.winllc.certalert.service;

/** Raised when a target name is already in use. Rendered as HTTP 409. */
public class DuplicateTargetException extends RuntimeException {

    public DuplicateTargetException(String name) {
        super("A certificate target named '" + name + "' already exists");
    }
}

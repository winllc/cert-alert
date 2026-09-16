package com.winllc.certalert.ldap;

/** Raised when an entry cannot be read out of the directory. */
public class DirectoryReadException extends RuntimeException {

    public DirectoryReadException(String message, Throwable cause) {
        super(message, cause);
    }
}

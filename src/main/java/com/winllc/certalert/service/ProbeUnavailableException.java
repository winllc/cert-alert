package com.winllc.certalert.service;

/**
 * There is nothing to probe, or nothing may be probed.
 *
 * <p>Distinct from an endpoint that could not be reached, which is an answer and is
 * reported as one. This is the request being impossible rather than the server being down:
 * an entry that says nowhere it lives, or probing switched off for this deployment.
 */
public class ProbeUnavailableException extends RuntimeException {

    private final String title;

    public ProbeUnavailableException(String title, String message) {
        super(message);
        this.title = title;
    }

    /** The short form, for the problem detail. */
    public String getTitle() {
        return title;
    }
}

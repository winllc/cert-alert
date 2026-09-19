package com.winllc.certalert.service;

/**
 * Something the caller asked to add is already there. Rendered as HTTP 409.
 *
 * <p>Carries its own title because the four things that raise it are not the same thing -
 * a point of contact, an address, a project, a managed attribute - and a problem detail
 * that says "Already a point of contact" over a duplicate project name is worse than one
 * that says nothing: it sends whoever reads it looking in the wrong place.
 */
public class AlreadyExistsException extends RuntimeException {

    private final String title;

    /**
     * @param title the short form, which names what already exists
     * @param message the long form, which names which one
     */
    public AlreadyExistsException(String title, String message) {
        super(message);
        this.title = title;
    }

    public String getTitle() {
        return title;
    }
}

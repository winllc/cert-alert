package com.winllc.certalert.domain;

/**
 * What kind of thing a managed attribute holds, which is what decides the control the page
 * draws and what a value is allowed to be.
 */
public enum ServerAttributeType {

    /** Anything somebody types. */
    TEXT("Free text", true),

    /** One of the values the definition lists, and nothing else. */
    CHOICE("Drop-down", true),

    /**
     * Set or not. A second value would have to contradict the first, so a boolean is
     * single-valued and cannot be made otherwise.
     */
    BOOLEAN("Yes or no", false);

    private final String label;
    private final boolean allowsMultipleValues;

    ServerAttributeType(String label, boolean allowsMultipleValues) {
        this.label = label;
        this.allowsMultipleValues = allowsMultipleValues;
    }

    public String label() {
        return label;
    }

    /** Whether this kind may be defined as holding more than one value. */
    public boolean allowsMultipleValues() {
        return allowsMultipleValues;
    }
}

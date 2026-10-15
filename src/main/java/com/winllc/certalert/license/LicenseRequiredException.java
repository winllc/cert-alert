package com.winllc.certalert.license;

/**
 * Something was asked of a licensed feature this deployment's license does not cover. A
 * refusal rather than a fault: {@code GlobalExceptionHandler} answers it with a 403, as
 * problem detail or as a page depending on who asked.
 */
public class LicenseRequiredException extends RuntimeException {

    private final LicensedFeature feature;

    public LicenseRequiredException(LicensedFeature feature) {
        super(feature.label() + " requires an ENS Solutions license.");
        this.feature = feature;
    }

    public LicensedFeature getFeature() {
        return feature;
    }
}

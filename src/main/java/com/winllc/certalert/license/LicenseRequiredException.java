package com.winllc.certalert.license;

import com.enssolutions.license.LicenseCheck;

/**
 * A request the license does not allow. Thrown by {@link LicenseInterceptor} rather than
 * written there, so the refusal goes through {@code GlobalExceptionHandler} and comes out
 * the way every other refusal does: problem detail for {@code /api}, a page for a browser.
 */
public class LicenseRequiredException extends RuntimeException {

    /** Why: what the problem detail's {@code error} says. */
    public enum Kind {
        /** No license this installation can use: only sign-in and the license screen work. */
        UNLICENSED("license_required", "License required"),
        /** Expired, or a perpetual license that does not cover this release: nothing can change. */
        READ_ONLY("license_read_only", "Read-only license"),
        /** The license does not include this feature. */
        FEATURE("feature_not_licensed", "Feature not licensed");

        private final String error;
        private final String title;

        Kind(String error, String title) {
            this.error = error;
            this.title = title;
        }

        public String error() {
            return error;
        }

        public String title() {
            return title;
        }
    }

    private final Kind kind;
    private final String state;
    private final String feature;

    private LicenseRequiredException(Kind kind, String state, String feature, String message) {
        super(message);
        this.kind = kind;
        this.state = state;
        this.feature = feature;
    }

    public static LicenseRequiredException unlicensed(LicenseCheck check) {
        return new LicenseRequiredException(Kind.UNLICENSED, check.state().name(), null, check.message());
    }

    public static LicenseRequiredException readOnly(LicenseCheck check) {
        return new LicenseRequiredException(Kind.READ_ONLY, check.state().name(), null, check.message());
    }

    public static LicenseRequiredException feature(LicenseCheck check, String feature) {
        return new LicenseRequiredException(Kind.FEATURE, check.state().name(), feature,
                LicensedFeature.labelOf(feature) + " is not included in this installation's license.");
    }

    public Kind getKind() {
        return kind;
    }

    /** The license state ({@code MISSING}, {@code EXPIRED}, ...). */
    public String getState() {
        return state;
    }

    /** The feature id, for a {@link Kind#FEATURE} refusal; null otherwise. */
    public String getFeature() {
        return feature;
    }

    /** The feature's name, for a page. */
    public String getFeatureLabel() {
        return feature == null ? null : LicensedFeature.labelOf(feature);
    }
}

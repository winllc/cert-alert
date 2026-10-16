package com.winllc.certalert.license;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller or handler method as part of a licensed feature, by its catalog id
 * ({@code "revocation-checks"}; see {@link LicensedFeature}). {@link LicenseInterceptor}
 * refuses requests to it with a 403 unless the installed license lists that feature.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresFeature {

    /** The feature's catalog id. */
    String value();
}

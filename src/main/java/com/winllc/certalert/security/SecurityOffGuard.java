package com.winllc.certalert.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * {@code cert-alert.security.enabled=false} turns off both sign-in chains and leaves every
 * page, every API and every administrative action open to anybody who can reach the port.
 * That is for working on the application on one's own machine, and nowhere else: outside the
 * {@code dev} profile the application refuses to start with it, rather than start open.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "cert-alert.security", name = "enabled", havingValue = "false")
public class SecurityOffGuard {

    public static final String DEV_PROFILE = "dev";

    public SecurityOffGuard(Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of(DEV_PROFILE))) {
            throw new IllegalStateException("cert-alert.security.enabled=false leaves the application open to anybody: "
                    + "it is allowed only with the '" + DEV_PROFILE + "' profile (local development). Remove it, "
                    + "or set it to true.");
        }
    }
}

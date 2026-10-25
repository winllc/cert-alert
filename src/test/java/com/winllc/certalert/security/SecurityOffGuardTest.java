package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Security off is for local development only: anywhere else the application will not start (CA-20). */
class SecurityOffGuardTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SecurityOffGuard.class);

    @Test
    void securityOffOutsideTheDevProfileRefusesToStart() {
        runner.withPropertyValues("cert-alert.security.enabled=false")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("cert-alert.security.enabled=false"));
        runner.withPropertyValues("cert-alert.security.enabled=false")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("postgres"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void securityOffStartsInTheDevProfile() {
        runner.withPropertyValues("cert-alert.security.enabled=false")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void securityOnStartsAnywhere() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(SecurityOffGuard.class));
        runner.withPropertyValues("cert-alert.security.enabled=true")
                .run(context -> assertThat(context).hasNotFailed());
    }
}

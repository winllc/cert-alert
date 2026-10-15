package com.winllc.certalert.license;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The license, and the gate in front of the addresses that need one.
 *
 * <p>Where the file is read from is configurable ({@code ens.license.file}, or
 * {@code ENS_LICENSE_FILE}); the key it is checked against is not - see
 * {@link LicenseVerifier}. A missing or bad file is not a startup failure: the application
 * runs with its core features and says why in the log.
 */
@Configuration(proxyBeanMethods = false)
public class LicenseConfig {

    public static final String PRODUCT = "cert-alert";

    @Bean
    public LicenseService licenseService(@Value("${ens.license.file:license.lic}") String file, Clock clock) {
        return new LicenseService(LicenseVerifier.forProduct(PRODUCT), Path.of(file), PRODUCT, clock);
    }

    /** Registers {@link LicenseInterceptor} on every address; it decides which it guards. */
    @Configuration(proxyBeanMethods = false)
    static class Web implements WebMvcConfigurer {

        private final LicenseService licenseService;

        Web(LicenseService licenseService) {
            this.licenseService = licenseService;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new LicenseInterceptor(licenseService));
        }
    }
}

package com.winllc.certalert.license;

import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseVerifier;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The ENS license: the {@link LicenseManager}, and the gate in front of every request.
 *
 * <p>Where the license and the installation's state are kept comes from the environment,
 * not from Spring configuration: {@code ENS_LICENSE_FILE} (default
 * {@code /etc/ens/certalert.lic}) and {@code ENS_LICENSE_STATE_DIR} (default
 * {@code /var/lib/ens/certalert}). The container image points both at its
 * {@code /var/lib/ens/certalert} volume. The keys a license is checked against are built in
 * ({@link EnsKeys}) and are not configurable at all.
 *
 * <p>The release date that perpetual licenses' maintenance is measured against is this
 * build's time, from Spring Boot's build-info.
 */
@Configuration(proxyBeanMethods = false)
public class LicensingConfiguration implements WebMvcConfigurer {

    /** This application's product id in the ENS licensing portal. */
    public static final String PRODUCT = "certalert";

    /** Where an administrator installs a license, and where an unlicensed one is sent. */
    public static final String LICENSE_SCREEN = "/admin/license";

    /** Where anybody else is sent while the application is unlicensed. */
    public static final String UNLICENSED_PAGE = "/unlicensed";

    /**
     * The audit trail stays available whatever the license says: the audit page, its API
     * (export, verify, tokens) and the SIEM pull API. It is not a licensed feature.
     */
    public static final String[] AUDIT_PATHS = {
        "/admin/audit", "/admin/audit/**", "/api/v1/admin/audit/**", "/api/audit/**"
    };

    private static final Logger log = LoggerFactory.getLogger(LicensingConfiguration.class);

    private final ObjectProvider<Licensing> licensing;

    public LicensingConfiguration(ObjectProvider<Licensing> licensing) {
        this.licensing = licensing;
    }

    @Bean
    public LicenseManager licenseManager(ObjectProvider<BuildProperties> build) {
        Optional<Instant> released = Optional.ofNullable(build.getIfAvailable()).map(BuildProperties::getTime);
        LicenseManager manager = LicenseManager.fromEnvironment(
                PRODUCT, LicenseVerifier.trusting(EnsKeys.PUBLIC_KEYS), released);
        log.info("ENS license file {}; release date {}", manager.licenseFile(),
                released.map(Instant::toString).orElse("unknown"));
        return manager;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LicenseInterceptor(licensing.getObject()))
                .excludePathPatterns(
                        "/login", "/logout", "/error", "/error/**",
                        LICENSE_SCREEN, LICENSE_SCREEN + "/**", UNLICENSED_PAGE, "/api/v1/license",
                        "/css/**", "/js/**", "/img/**", "/webjars/**", "/favicon.ico",
                        "/actuator/**")
                .excludePathPatterns(AUDIT_PATHS);
    }
}

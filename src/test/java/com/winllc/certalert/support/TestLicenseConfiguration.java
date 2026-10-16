package com.winllc.certalert.support;

import com.enssolutions.license.LicenseManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Gives every test context (the {@code test} profile) a license: a {@link LicenseManager}
 * that trusts the ENS portal's published TEST key instead of the production one, keeping its
 * license file and installation state in a temporary directory, with a full license for
 * every feature installed. Primary, so it replaces the application's own everywhere.
 *
 * <p>Picked up by the application's component scan, which reaches the test classes when the
 * tests run - and only then, and only with this profile. Tests that change the license put
 * the full one back afterwards ({@link TestLicenses#restoreFull}): contexts are shared.
 */
@Configuration(proxyBeanMethods = false)
@Profile("test")
public class TestLicenseConfiguration {

    @Bean
    @Primary
    public LicenseManager testLicenseManager() throws IOException {
        Path dir = Files.createTempDirectory("cert-alert-license");
        dir.toFile().deleteOnExit();
        LicenseManager licenses = new LicenseManager(
                TestLicenses.PRODUCT,
                TestLicenses.verifier(),
                dir.resolve("license.lic"),
                dir.resolve("state"),
                Optional.empty(),
                Clock.systemUTC());
        TestLicenses.restoreFull(licenses);
        return licenses;
    }
}

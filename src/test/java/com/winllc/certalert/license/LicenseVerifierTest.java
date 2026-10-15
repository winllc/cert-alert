package com.winllc.certalert.license;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.winllc.certalert.support.TestLicenses;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The license check, against the test key that shadows the real one on the test classpath.
 *
 * <p>No Spring here: the verifier and the service are plain classes, and what they decide
 * is the whole of what licensing means.
 */
class LicenseVerifierTest {

    private static final String PRODUCT = LicenseConfig.PRODUCT;

    private final LicenseVerifier verifier = LicenseVerifier.forProduct(PRODUCT);

    @TempDir
    Path dir;

    @Test
    void acceptsTheShippedTestLicense() throws Exception {
        License license = verifier.verify(Files.readString(Path.of("src/test/resources/license/test.lic")));

        assertThat(license.product()).isEqualTo(PRODUCT);
        assertThat(license.licensee()).isEqualTo("Test Suite");
        assertThat(license.expires()).isNull();
        assertThat(license.coversAllFeatures()).isTrue();
        assertThat(license.covers(LicensedFeature.METRICS.id())).isTrue();
    }

    @Test
    void acceptsALicenseForSomeFeatures() {
        License license = verifier.verify(TestLicenses.sign(PRODUCT, "metrics,projects", LocalDate.of(2030, 1, 1)));

        assertThat(license.features()).containsExactlyInAnyOrder("metrics", "projects");
        assertThat(license.covers("metrics")).isTrue();
        assertThat(license.covers("revocation")).isFalse();
        assertThat(license.expires()).isEqualTo(LocalDate.of(2030, 1, 1));
    }

    @Test
    void rejectsATamperedLicense() {
        String signed = TestLicenses.sign(PRODUCT, "metrics", LocalDate.of(2030, 1, 1));
        String tampered = signed.replace("features=metrics", "features=*");

        assertThatThrownBy(() -> verifier.verify(tampered))
                .isInstanceOf(LicenseVerifier.InvalidLicenseException.class)
                .hasMessageContaining("signature does not match");
    }

    @Test
    void rejectsALicenseForAnotherProduct() {
        String other = TestLicenses.sign("readyroom", "*", null);

        assertThatThrownBy(() -> verifier.verify(other))
                .isInstanceOf(LicenseVerifier.InvalidLicenseException.class)
                .hasMessageContaining("for readyroom, not cert-alert");
    }

    @Test
    void rejectsALicenseWithNoSignature() {
        String unsigned = TestLicenses.sign(PRODUCT, "*", null).replaceAll("(?m)^signature=.*$", "");

        assertThatThrownBy(() -> verifier.verify(unsigned))
                .isInstanceOf(LicenseVerifier.InvalidLicenseException.class)
                .hasMessageContaining("no signature");
    }

    @Test
    void rejectsASignedLicenseMissingAField() {
        Map<String, String> fields = new TreeMap<>(Map.of(
                "licenseId", "x", "product", PRODUCT, "licensee", "Test Suite", "issued", "2026-01-01",
                "expires", "never"));

        assertThatThrownBy(() -> verifier.verify(TestLicenses.sign(fields)))
                .isInstanceOf(LicenseVerifier.InvalidLicenseException.class)
                .hasMessageContaining("no features");
    }

    @Test
    void anExpiredLicenseFallsBackToCore() {
        Path file = TestLicenses.write(dir.resolve("license.lic"),
                TestLicenses.sign(PRODUCT, "*", LocalDate.of(2026, 6, 30)));

        // Good through the end of its last day, UTC...
        LicenseService lastDay = service(file, "2026-06-30T23:59:00Z");
        assertThat(lastDay.status().state()).isEqualTo(LicenseService.State.LICENSED);
        assertThat(lastDay.isEnabled("metrics")).isTrue();

        // ...and not a minute after.
        LicenseService after = service(file, "2026-07-01T00:01:00Z");
        LicenseService.Status status = after.status();
        assertThat(status.state()).isEqualTo(LicenseService.State.EXPIRED);
        assertThat(status.features()).isEmpty();
        assertThat(status.problem()).contains("expired on 2026-06-30");
        assertThat(after.isEnabled("metrics")).isFalse();
    }

    @Test
    void aMissingFileIsCoreOnlyNotAFailure() {
        LicenseService service = service(dir.resolve("nothing-here.lic"), "2026-10-15T00:00:00Z");

        assertThat(service.status().state()).isEqualTo(LicenseService.State.MISSING);
        assertThat(service.status().problem()).isNull();
        assertThat(service.isEnabled("metrics")).isFalse();
    }

    @Test
    void aBadFileIsInvalidAndSaysWhy() throws Exception {
        Path file = dir.resolve("license.lic");
        Files.writeString(file, "this is not a license", StandardCharsets.UTF_8);

        LicenseService.Status status = service(file, "2026-10-15T00:00:00Z").status();

        assertThat(status.state()).isEqualTo(LicenseService.State.INVALID);
        assertThat(status.problem()).isNotBlank();
    }

    @Test
    void featureIdsAreUnique() {
        assertThat(LicensedFeature.values())
                .extracting(LicensedFeature::id)
                .doesNotHaveDuplicates()
                .allSatisfy(id -> assertThat(LicensedFeature.byId(id)).isPresent());
    }

    private LicenseService service(Path file, String instant) {
        return new LicenseService(verifier, file, PRODUCT, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }
}

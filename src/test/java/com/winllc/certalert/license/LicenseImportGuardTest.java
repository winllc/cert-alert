package com.winllc.certalert.license;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * An upgraded installation whose license volume is not mounted, and whose database holds no
 * installation id yet, does not start with a new one by accident.
 */
@SpringBootTest
@ActiveProfiles("test")
class LicenseImportGuardTest {

    private static final String PRODUCT = "certalert-guard-test";

    @Autowired
    private JdbcTemplate jdbc;

    private Timestamp originallyInstalled;
    private Path volume;

    @BeforeEach
    void pretendUpgraded() throws Exception {
        originallyInstalled = jdbc.queryForObject(
                "SELECT \"installed_on\" FROM \"flyway_schema_history\" WHERE \"version\" = '20'", Timestamp.class);
        jdbc.update("UPDATE \"flyway_schema_history\" SET \"installed_on\" = ? WHERE \"version\" = '20'",
                Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        volume = Files.createTempDirectory("license-state");
    }

    @AfterEach
    void restore() {
        jdbc.update("UPDATE \"flyway_schema_history\" SET \"installed_on\" = ? WHERE \"version\" = '20'",
                originallyInstalled);
        jdbc.update("DELETE FROM ens_license_installation WHERE product = ?", PRODUCT);
    }

    @Test
    void anUpgradeWithoutTheVolumeIsRefused() {
        assertThatThrownBy(() -> LicenseImportGuard.check(jdbc, PRODUCT, volume, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("new installation id");
    }

    @Test
    void anUpgradeWithTheVolumeGoesAhead() throws Exception {
        Files.writeString(volume.resolve("installation-id"), "0123456789abcdef\n");
        assertThatCode(() -> LicenseImportGuard.check(jdbc, PRODUCT, volume, false)).doesNotThrowAnyException();
    }

    @Test
    void anIdAlreadyInTheDatabaseOrSayingSoGoesAhead() {
        assertThatCode(() -> LicenseImportGuard.check(jdbc, PRODUCT, volume, true)).doesNotThrowAnyException();
        jdbc.update("INSERT INTO ens_license_installation (product, installation_id) VALUES (?, ?)", PRODUCT, "abc");
        assertThatCode(() -> LicenseImportGuard.check(jdbc, PRODUCT, volume, false)).doesNotThrowAnyException();
    }

    @Test
    void aFreshDatabaseGoesAhead() {
        restore();
        assertThatCode(() -> LicenseImportGuard.check(jdbc, PRODUCT, volume, false)).doesNotThrowAnyException();
    }
}

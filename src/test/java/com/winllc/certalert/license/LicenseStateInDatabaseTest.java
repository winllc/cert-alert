package com.winllc.certalert.license;

import static org.assertj.core.api.Assertions.assertThat;

import com.enssolutions.license.JdbcLicenseStore;
import com.enssolutions.license.LicenseCheck;
import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseState;
import com.winllc.certalert.support.TestLicenses;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The license state in the database (V25, the library's JdbcLicenseStore), as the
 * application wires it: shared by every replica, and adopted from the license volume of an
 * installation upgraded from the file-based releases.
 *
 * <p>Two managers on the one database stand in for two replicas.
 */
@SpringBootTest
@ActiveProfiles("test")
class LicenseStateInDatabaseTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    private Path volume;

    @BeforeEach
    void clear() throws Exception {
        forget();
        volume = Files.createTempDirectory("license-volume");
    }

    @AfterEach
    void forget() {
        jdbc.update("DELETE FROM ens_license_revision WHERE product = ?", TestLicenses.PRODUCT);
        jdbc.update("DELETE FROM ens_license_file WHERE product = ?", TestLicenses.PRODUCT);
        jdbc.update("DELETE FROM ens_license_installation WHERE product = ?", TestLicenses.PRODUCT);
    }

    /** A replica, as LicensingConfiguration makes one, rechecking the database on every read. */
    private LicenseManager replica() {
        return new LicenseManager(
                TestLicenses.PRODUCT,
                TestLicenses.verifier(),
                new JdbcLicenseStore(dataSource, TestLicenses.PRODUCT, Duration.ZERO)
                        .importingFileStateOnFirstUse(volume.resolve("license.lic"), volume.resolve("state")),
                Optional.empty(),
                Clock.systemUTC(),
                Optional.empty());
    }

    @Test
    void anUpgradedInstallationKeepsItsIdAndLicenseAndEveryReplicaSharesThem() {
        // What a file-based release left on the license volume.
        LicenseManager before = new LicenseManager(TestLicenses.PRODUCT, TestLicenses.verifier(),
                volume.resolve("license.lic"), volume.resolve("state"), Optional.empty(), Clock.systemUTC());
        String id = before.installationId();
        TestLicenses.install(before, TestLicenses.license().installation(id).revision(3).sign());

        LicenseManager first = replica();
        LicenseManager second = replica();

        assertThat(first.installationId()).isEqualTo(id);
        assertThat(second.installationId()).isEqualTo(id);
        LicenseCheck check = second.current();
        assertThat(check.state()).isEqualTo(LicenseState.VALID);
        assertThat(check.license()).hasValueSatisfying(license -> assertThat(license.revision()).isEqualTo(3));
        assertThat(first.licenseLocation()).contains("database");
    }

    @Test
    void anUploadOnOneReplicaIsInForceOnTheOthers() {
        LicenseManager first = replica();
        LicenseManager second = replica();
        String id = first.installationId();
        assertThat(second.installationId()).isEqualTo(id);

        TestLicenses.install(first, TestLicenses.license().id("lic_shared").installation(id).revision(4).sign());
        assertThat(second.current().license()).hasValueSatisfying(license -> assertThat(license.revision()).isEqualTo(4));

        // The revisions seen are shared too: an older one is refused on any replica, and the
        // installed one stays.
        LicenseCheck older = TestLicenses.install(second,
                TestLicenses.license().id("lic_shared").installation(id).revision(2).sign());
        assertThat(older.state()).isEqualTo(LicenseState.SUPERSEDED);
        assertThat(first.current().license()).hasValueSatisfying(license -> assertThat(license.revision()).isEqualTo(4));
    }
}

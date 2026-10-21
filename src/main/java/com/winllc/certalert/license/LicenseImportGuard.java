package com.winllc.certalert.license;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Refuses to let an upgraded installation start with a new installation id by accident.
 *
 * <p>The license library adopts the installation id from the license volume the first time
 * it is used, if the database has none yet - and otherwise makes up a new one, which the
 * installed license is not bound to, and after which the old id can no longer be imported. On
 * a fresh installation a new id is right. On one upgraded from a release that kept the id on
 * the volume, it is right only if the volume really is gone; if it simply was not mounted (a
 * chart scaled straight to several replicas, say), starting would quietly unbind the license.
 *
 * <p>So: where the database holds no installation id, the volume holds none either, and the
 * database is one this release upgraded rather than created, startup stops and says why -
 * unless {@code cert-alert.license.allow-new-installation-id} says a new id is what is wanted.
 */
final class LicenseImportGuard {

    private static final Logger log = LoggerFactory.getLogger(LicenseImportGuard.class);

    /** The last migration of the releases that kept the license state in files. */
    static final String LAST_FILE_RELEASE_MIGRATION = "20";

    /** The migration that brought the license state into the database. */
    static final String DATABASE_STORE_MIGRATION = "25";

    /**
     * A database migrated from empty runs every migration within moments; one that ran these
     * two this far apart was upgraded.
     */
    private static final Duration UPGRADE_GAP = Duration.ofMinutes(10);

    private LicenseImportGuard() {}

    static void check(JdbcTemplate jdbc, String product, Path stateDir, boolean allowNewId) {
        if (allowNewId || hasInstallationId(jdbc, product) || Files.exists(stateDir.resolve("installation-id"))) {
            return;
        }
        if (!upgraded(jdbc)) {
            return;
        }
        throw new IllegalStateException("This installation was upgraded from a release that kept its license "
                + "installation id on the license volume, and neither the database nor " + stateDir
                + " holds one. Starting now would give it a new installation id, which its license is not bound "
                + "to. Mount the license volume at " + stateDir + " (ENS_LICENSE_STATE_DIR) for this start - in "
                + "Helm, upgrade with replicaCount 1 first - so the id is imported. If the volume is really "
                + "gone, set CERT_ALERT_LICENSE_ALLOW_NEW_INSTALLATION_ID=true and re-bind the license on the "
                + "ENS portal.");
    }

    private static boolean hasInstallationId(JdbcTemplate jdbc, String product) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ens_license_installation WHERE product = ?", Integer.class, product);
        return count != null && count > 0;
    }

    private static boolean upgraded(JdbcTemplate jdbc) {
        try {
            Timestamp before = installedOn(jdbc, LAST_FILE_RELEASE_MIGRATION);
            Timestamp after = installedOn(jdbc, DATABASE_STORE_MIGRATION);
            return before != null && after != null
                    && Duration.between(before.toInstant(), after.toInstant()).compareTo(UPGRADE_GAP) > 0;
        } catch (DataAccessException e) {
            log.debug("Could not read the migration history; not checking the license import: {}", e.toString());
            return false;
        }
    }

    private static Timestamp installedOn(JdbcTemplate jdbc, String version) {
        List<Timestamp> on = jdbc.queryForList(
                "SELECT \"installed_on\" FROM \"flyway_schema_history\" WHERE \"version\" = ? AND \"success\" = TRUE",
                Timestamp.class, version);
        return on.isEmpty() ? null : on.getFirst();
    }
}

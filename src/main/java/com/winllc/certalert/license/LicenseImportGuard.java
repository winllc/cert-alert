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
 * <p>So: where the database holds no installation id and the volume holds none either,
 * startup stops and says why if
 * <ul>
 *   <li>the database is one this release upgraded rather than created, or
 *   <li>{@code ENS_LICENSE_EXPECT_INSTALLATION_ID=true} says the deployment did not mount the
 *       license volume - which the chart sets whenever it leaves the volume out. That covers an
 *       existing installation whose database is new (rebuilt, or never had one) going straight
 *       to several replicas: there the migration history looks fresh, and a pod without the
 *       file could otherwise win the race to insert a new id.
 * </ul>
 * {@code cert-alert.license.allow-new-installation-id} overrides this only while the database
 * holds no license state at all, and is logged as an error on every start while it is set.
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
        check(jdbc, product, stateDir, allowNewId, false);
    }

    /**
     * @param expectInstallationId the deployment says it did not mount the license volume, so an
     *     id must already be in the database ({@code ENS_LICENSE_EXPECT_INSTALLATION_ID})
     */
    static void check(JdbcTemplate jdbc, String product, Path stateDir, boolean allowNewId,
            boolean expectInstallationId) {
        boolean anyState = hasLicenseState(jdbc, product);
        if (allowNewId) {
            if (anyState) {
                log.error("cert-alert.license.allow-new-installation-id is set, and ignored: the database already "
                        + "holds license state for {}. Unset it.", product);
            } else {
                log.error("cert-alert.license.allow-new-installation-id is set: a new installation id may be made "
                        + "for {}, and a license bound to an earlier one stops matching. Unset it once this "
                        + "installation has started.", product);
                return;
            }
        }
        if (hasInstallationId(jdbc, product) || Files.exists(stateDir.resolve("installation-id"))) {
            return;
        }
        if (expectInstallationId) {
            throw new IllegalStateException("ENS_LICENSE_EXPECT_INSTALLATION_ID is set - the license volume is not "
                    + "mounted here - and the database holds no installation id for " + product + ". Starting "
                    + "now would make up a new one, which this installation's license is not bound to. Start it "
                    + "once with the license volume mounted (in Helm, replicaCount 1) so the id is imported; for a "
                    + "new installation, start with one replica first, or set "
                    + "CERT_ALERT_LICENSE_ALLOW_NEW_INSTALLATION_ID=true for that start.");
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

    /** Any license state for this product: an id, a license, a revision. */
    private static boolean hasLicenseState(JdbcTemplate jdbc, String product) {
        for (String table : new String[] {"ens_license_installation", "ens_license_file", "ens_license_revision"}) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE product = ?", Integer.class, product);
            if (count != null && count > 0) {
                return true;
            }
        }
        return false;
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

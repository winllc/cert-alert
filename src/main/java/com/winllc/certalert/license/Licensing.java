package com.winllc.certalert.license;

import com.enssolutions.license.LicenseCheck;
import com.enssolutions.license.LicenseManager;
import com.enssolutions.license.LicenseState;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What the application asks about its license: the one place jobs, services and templates
 * go to, so none of them handles a {@link LicenseManager} directly. Templates reach it as
 * {@code @licensing}.
 *
 * <ul>
 *   <li>{@link #isEnabled} - may this feature <em>do</em> something now: full mode and
 *       listed. Jobs, syncs, outgoing email and the buttons that start them ask this.
 *   <li>{@link #shows} - may what this feature has gathered be <em>shown</em>: listed, and
 *       the application is not unlicensed. In read-only mode data stays visible, so menus,
 *       cards and pages ask this.
 *   <li>{@link #mayRun} - {@code isEnabled} for a scheduled job, which says once in the log
 *       why it is standing down.
 *   <li>{@link #mayAdd} - the size limit, for the directory sync.
 * </ul>
 *
 * <p>{@link LicenseManager#current()} is cheap (it re-reads the file only when it changes),
 * so everything here asks it afresh: a license uploaded or renewed takes effect at once.
 */
@Component("licensing")
public class Licensing {

    private static final Logger log = LoggerFactory.getLogger(Licensing.class);

    private final LicenseManager licenses;

    /** Jobs that have already said they are standing down. */
    private final Set<String> standingDown = ConcurrentHashMap.newKeySet();

    public Licensing(LicenseManager licenses) {
        this.licenses = licenses;
    }

    public LicenseManager manager() {
        return licenses;
    }

    public LicenseCheck current() {
        return licenses.current();
    }

    /** Full mode, and the license lists the feature. */
    public boolean isEnabled(String feature) {
        return licenses.current().isFeatureEnabled(feature);
    }

    /** Full or read-only mode, and the license lists the feature. */
    public boolean shows(String feature) {
        return shows(licenses.current(), feature);
    }

    static boolean shows(LicenseCheck check, String feature) {
        if (check.mode() == LicenseState.Mode.UNLICENSED || check.license().isEmpty()) {
            return false;
        }
        String id = feature.contains(".") ? feature : check.license().get().product() + "." + feature;
        return check.license().get().features().contains(id);
    }

    /** Full mode: the license is valid or in its grace period. */
    public boolean isFullyLicensed() {
        return licenses.current().isFullyLicensed();
    }

    /**
     * Whether a scheduled job may run: full mode, and its feature licensed. Says so once when
     * it may not, and again after it could and then could not, rather than once per firing.
     */
    public boolean mayRun(String feature, String job) {
        LicenseCheck check = licenses.current();
        if (check.isFeatureEnabled(feature)) {
            standingDown.remove(job);
            return true;
        }
        if (standingDown.add(job)) {
            log.info("Skipping the scheduled {} job: {}", job, check.isFullyLicensed()
                    ? LicensedFeature.labelOf(feature) + " is not included in the license"
                    : check.message());
        }
        return false;
    }

    /** Whether housekeeping with no feature of its own may run: full mode. */
    public boolean mayRun(String job) {
        LicenseCheck check = licenses.current();
        if (check.isFullyLicensed()) {
            standingDown.remove(job);
            return true;
        }
        if (standingDown.add(job)) {
            log.info("Skipping the scheduled {} job: {}", job, check.message());
        }
        return false;
    }

    /** Whether one more directory user may be added to {@code currentCount}. */
    public boolean mayAdd(long currentCount) {
        return licenses.current().mayAdd(currentCount);
    }

    /** "1,234 of 2,500 directory users." and so on, for the license screen and the log. */
    public String usageMessage(long count) {
        return licenses.current().usageMessage(count);
    }

    public String installationId() {
        return licenses.installationId();
    }
}

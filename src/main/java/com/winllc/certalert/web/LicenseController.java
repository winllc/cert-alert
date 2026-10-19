package com.winllc.certalert.web;

import com.enssolutions.license.EnsLicense;
import com.enssolutions.license.LicenseCheck;
import com.winllc.certalert.license.Licensing;
import com.winllc.certalert.license.LicensedFeature;
import com.winllc.certalert.repository.DirectoryUserRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The license as it stands, as JSON. Open to anyone signed in - it explains why a page they
 * expected is not there - and answered whatever the license says, unlicensed included. The
 * installation id and where the file lives are for administrators.
 */
@RestController
@RequestMapping("/api/v1")
public class LicenseController {

    private final Licensing licensing;
    private final DirectoryUserRepository users;
    private final CurrentUserAdvice currentUser;

    public LicenseController(Licensing licensing, DirectoryUserRepository users, CurrentUserAdvice currentUser) {
        this.licensing = licensing;
        this.users = users;
        this.currentUser = currentUser;
    }

    /**
     * @param state VALID, GRACE, EXPIRED, RELEASE_NOT_COVERED, NOT_YET_VALID, WRONG_PRODUCT,
     *     WRONG_INSTALLATION, SUPERSEDED, MISSING or INVALID
     * @param mode FULL, READ_ONLY or UNLICENSED
     * @param features the feature ids the license lists (without the product prefix)
     * @param usage directory users counted against the license's size
     * @param installationId this installation's id, administrators only
     * @param licenseFile where the license is read from, administrators only
     */
    public record Status(
            String product,
            String state,
            String mode,
            String message,
            String licenseId,
            Integer revision,
            String edition,
            String licensee,
            Instant notBefore,
            Instant expiresAt,
            Integer graceDays,
            Instant maintenanceUntil,
            Long daysLeft,
            boolean evaluation,
            boolean renewalWarning,
            List<String> features,
            Size size,
            String installationId,
            String licenseFile) {}

    /** How many of the licensed size are in use. */
    public record Size(Long quantity, String unit, long used, String usage, String message) {}

    @GetMapping("/license")
    public Status license() {
        LicenseCheck check = licensing.current();
        EnsLicense l = check.license().orElse(null);
        long used = users.count();
        boolean admin = currentUser.isAdmin();
        return new Status(
                licensing.manager().product(),
                check.state().name(),
                check.mode().name(),
                check.message(),
                l == null ? null : l.id(),
                l == null ? null : l.revision(),
                l == null ? null : l.edition(),
                l == null ? null : l.licensee(),
                l == null ? null : l.notBefore(),
                l == null ? null : l.expiresAt().orElse(null),
                l == null ? null : l.graceDays(),
                l == null ? null : l.maintenanceUntil().orElse(null),
                check.daysLeft().orElse(null),
                check.showEvaluationBanner(),
                check.showRenewalWarning(),
                l == null ? List.of() : Arrays.stream(LicensedFeature.values())
                        .map(LicensedFeature::id)
                        .filter(id -> l.features().contains(l.product() + "." + id))
                        .toList(),
                new Size(
                        l == null ? null : l.limitQuantity(),
                        l == null ? "directory users" : l.limitUnit(),
                        used,
                        l == null ? null : check.usage(used).name(),
                        check.usageMessage(used)),
                admin ? licensing.installationId() : null,
                admin ? licensing.manager().licenseLocation() : null);
    }
}

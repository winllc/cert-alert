package com.winllc.certalert.web;

import com.winllc.certalert.license.LicenseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What this deployment is licensed for. Open to anyone signed in, since it explains why a
 * page they expected is not there; where the file lives on the host is for administrators.
 */
@RestController
@RequestMapping("/api/v1")
public class LicenseController {

    private final LicenseService licenseService;
    private final CurrentUserAdvice currentUser;

    public LicenseController(LicenseService licenseService, CurrentUserAdvice currentUser) {
        this.licenseService = licenseService;
        this.currentUser = currentUser;
    }

    @GetMapping("/license")
    public LicenseService.Status license() {
        LicenseService.Status status = licenseService.status();
        if (currentUser.isAdmin()) {
            return status;
        }
        return new LicenseService.Status(
                status.state(),
                status.product(),
                status.licensee(),
                status.licenseId(),
                status.expires(),
                status.features(),
                status.problem(),
                null);
    }
}

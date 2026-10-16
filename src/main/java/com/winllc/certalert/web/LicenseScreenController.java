package com.winllc.certalert.web;

import com.enssolutions.license.LicenseCheck;
import com.enssolutions.license.LicenseException;
import com.enssolutions.license.LicenseState;
import com.winllc.certalert.audit.LicenseAudit;
import com.winllc.certalert.license.LicenseInstalled;
import com.winllc.certalert.license.Licensing;
import com.winllc.certalert.license.LicensedFeature;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.service.AuditActors;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Administration → License: what is installed, this installation's id (which a customer
 * enters on the ENS portal to bind a license to it), and the upload form.
 *
 * <p>Administrators only - the filter chain keeps {@code /admin/license} to them - and
 * reachable whatever the license says, which is the point of it: an unlicensed or expired
 * installation sends its administrators here. Everyone else is sent to
 * {@code /unlicensed}, which says to ask one.
 */
@Controller
public class LicenseScreenController {

    private static final Logger log = LoggerFactory.getLogger(LicenseScreenController.class);

    /** Far bigger than any license; anything larger is not one. */
    private static final long MAX_LICENSE_BYTES = 64 * 1024;

    private final Licensing licensing;
    private final DirectoryUserRepository users;
    private final CurrentUserAdvice currentUser;
    private final ApplicationEventPublisher events;
    private final LicenseAudit audit;

    public LicenseScreenController(
            Licensing licensing,
            DirectoryUserRepository users,
            CurrentUserAdvice currentUser,
            ApplicationEventPublisher events,
            LicenseAudit audit) {
        this.licensing = licensing;
        this.users = users;
        this.currentUser = currentUser;
        this.events = events;
        this.audit = audit;
    }

    /** One catalog feature, and whether the installed license lists it. */
    public record FeatureRow(String id, String label, String description, boolean enterprise, boolean included) {}

    @GetMapping("/admin/license")
    public String screen(Model model) {
        LicenseCheck check = licensing.current();
        long people = users.count();
        model.addAttribute("check", check);
        model.addAttribute("installationId", licensing.installationId());
        model.addAttribute("licenseFile", licensing.manager().licenseFile().toString());
        model.addAttribute("features", features(check));
        model.addAttribute("people", people);
        model.addAttribute("usage", check.license().isPresent() ? check.usage(people).name() : null);
        model.addAttribute("usageMessage", check.usageMessage(people));
        return "license";
    }

    /**
     * Installs an uploaded (or pasted) license if this installation can use it. A file for
     * another product, bound to another installation, or older than one already seen here is
     * refused and the installed license stays; anything else replaces it, and the screen says
     * what state that leaves the application in.
     */
    @PostMapping("/admin/license")
    public String upload(
            @RequestParam(name = "file", required = false) MultipartFile file,
            @RequestParam(name = "text", required = false) String text,
            RedirectAttributes flash) {
        String license;
        try {
            license = file != null && !file.isEmpty()
                    ? (file.getSize() > MAX_LICENSE_BYTES ? null : new String(file.getBytes(), StandardCharsets.UTF_8))
                    : text;
        } catch (IOException e) {
            flash.addFlashAttribute("error", "The file could not be read: " + e.getMessage());
            return "redirect:/admin/license";
        }
        if (license == null || license.isBlank() || license.length() > MAX_LICENSE_BYTES) {
            if (license == null || license.length() > MAX_LICENSE_BYTES) {
                audit.rejected("the upload is too large to be a license", null);
            }
            flash.addFlashAttribute("error", license == null
                    ? "That file is too large to be a license."
                    : "Choose a license file (.lic) or paste one.");
            return "redirect:/admin/license";
        }
        try {
            LicenseCheck check = licensing.manager().install(license);
            boolean refused = switch (check.state()) {
                case WRONG_PRODUCT, WRONG_INSTALLATION, SUPERSEDED -> true;
                default -> false;
            };
            if (refused) {
                log.warn("{} uploaded a license that was refused ({}): {}",
                        AuditActors.current(AuditActors.SYSTEM), check.state(), check.message());
                audit.rejected(check.message(), check);
                flash.addFlashAttribute("error", "That license was not installed. " + check.message());
            } else {
                log.info("{} installed a license: {} ({})",
                        AuditActors.current(AuditActors.SYSTEM), check.message(), check.state());
                audit.installed(check);
                flash.addFlashAttribute(check.isFullyLicensed() ? "notice" : "error",
                        "License installed. " + check.message());
                events.publishEvent(new LicenseInstalled(check));
            }
        } catch (LicenseException e) {
            audit.rejected("not a valid ENS license: " + e.getMessage(), null);
            flash.addFlashAttribute("error", "That isn't a valid ENS license: " + e.getMessage() + ".");
        } catch (IOException | RuntimeException e) {
            log.error("Could not install the uploaded license", e);
            flash.addFlashAttribute("error", "The license could not be saved: " + e.getMessage());
        }
        return "redirect:/admin/license";
    }

    /** For anybody who is not an administrator, while the application is unlicensed. */
    @GetMapping("/unlicensed")
    public String unlicensed(Model model) {
        LicenseCheck check = licensing.current();
        if (check.mode() != LicenseState.Mode.UNLICENSED) {
            return "redirect:/";
        }
        if (currentUser.isAdmin()) {
            return "redirect:/admin/license";
        }
        return "unlicensed";
    }

    private static List<FeatureRow> features(LicenseCheck check) {
        return Arrays.stream(LicensedFeature.values())
                .map(f -> new FeatureRow(f.id(), f.label(), f.description(), f.enterprise(),
                        check.license()
                                .map(l -> l.features().contains(l.product() + "." + f.id()))
                                .orElse(false)))
                .toList();
    }
}

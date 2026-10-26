package com.winllc.certalert.web;

import com.winllc.certalert.audit.AuditAdminController;
import com.winllc.certalert.demo.DemoAccounts;
import com.winllc.certalert.demo.DemoProperties;
import com.enssolutions.license.LicenseCheck;
import com.winllc.certalert.license.Licensing;
import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.security.DirectoryPrincipalResolver;
import com.winllc.certalert.security.ReadOnlyRoleFilter;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.webmvc.autoconfigure.error.BasicErrorController;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Puts the signed-in person on every page, so the bar can say who they are and how they
 * got in. Read from the context rather than injected, so it works on pages that have no
 * other reason to know about security.
 *
 * <p>Boot's error dispatch is listed alongside the application's own controller so that a
 * page served after something went wrong still carries the account menu. The exception
 * handlers call these methods directly: model attributes from an advice are not applied
 * when one runs, so nothing here would reach those pages otherwise.
 *
 * <p>The license check rides along for the same reason: the bar hides what the license
 * does not include, and carries the evaluation banner and the renewal warning.
 */
@ControllerAdvice(assignableTypes = {
    ViewController.class, LicenseScreenController.class, AuditAdminController.class, BasicErrorController.class
})
public class CurrentUserAdvice {

    private final Licensing licensing;
    private final DemoProperties demoProperties;
    /** Absent unless this is a demo, which is the only thing that offers accounts. */
    private final ObjectProvider<DemoAccounts> demoAccounts;
    private final ObjectProvider<com.winllc.certalert.security.EnsSecurityProperties> ens;

    public CurrentUserAdvice(
            Licensing licensing,
            DemoProperties demoProperties,
            ObjectProvider<DemoAccounts> demoAccounts,
            ObjectProvider<com.winllc.certalert.security.EnsSecurityProperties> ens) {
        this.licensing = licensing;
        this.demoProperties = demoProperties;
        this.demoAccounts = demoAccounts;
        this.ens = ens;
    }

    /**
     * How many seconds without a request end this person's session, so the page can warn
     * them before it does and let them stay (WCAG 2.2.1, timing adjustable). Null where
     * nobody is signed in or no idle limit applies.
     */
    @ModelAttribute("idleTimeoutSeconds")
    public Long idleTimeoutSeconds() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        com.winllc.certalert.security.EnsSecurityProperties properties = ens.getIfAvailable();
        if (properties == null || authentication == null || !authentication.isAuthenticated()
                || authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken) {
            return null;
        }
        java.time.Duration idle = com.winllc.certalert.security.SignedInDirectoryUser.isAdmin(authentication)
                ? properties.getSession().effectiveAdminIdleTimeout()
                : properties.getSession().getIdleTimeout();
        return idle == null || idle.isZero() || idle.isNegative() ? null : idle.toSeconds();
    }

    @ModelAttribute("currentUser")
    public DirectoryPrincipal currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof DirectoryPrincipal principal) {
            return principal;
        }
        return null;
    }

    /**
     * Whether this copy is a demo, so the pages can say so.
     *
     * <p>Every page: somebody arriving at a link in the middle of the application has to
     * be able to tell that what they are looking at cannot be changed, without having
     * pressed anything to find out.
     */
    @ModelAttribute("demo")
    public boolean demo() {
        return demoProperties.isEnabled();
    }

    /**
     * The accounts a demo offers, for the sign-in page to print, and empty everywhere else.
     *
     * <p>Empty rather than absent so the page has one thing to ask about. On a real
     * deployment there is nothing to list, and a page that printed credentials because a
     * property was misread is exactly the failure worth making impossible: the list comes
     * from the demo being on, not from the page deciding to show it.
     */
    @ModelAttribute("demoAccounts")
    public List<DemoAccounts.Account> demoAccounts() {
        DemoAccounts accounts = demoAccounts.getIfAvailable();
        return accounts == null ? List.of() : accounts.all();
    }

    /** The one password those accounts share, or empty where there are none. */
    @ModelAttribute("demoPassword")
    public String demoPassword() {
        return demoProperties.isEnabled() ? demoProperties.getPassword() : "";
    }

    @ModelAttribute("isAdmin")
    public boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> DirectoryPrincipalResolver.ROLE_ADMIN.equals(authority.getAuthority()));
    }

    /** An auditor and not an administrator: reads everything, changes nothing. */
    @ModelAttribute("isAuditor")
    public boolean isAuditor() {
        return ReadOnlyRoleFilter.isReadOnly(SecurityContextHolder.getContext().getAuthentication());
    }

    /** May read what administrators read: the administration page, the audit log, the license. */
    @ModelAttribute("canReadAdmin")
    public boolean canReadAdmin() {
        return isAdmin() || isAuditor();
    }

    @ModelAttribute("license")
    public LicenseCheck license() {
        return licensing.current();
    }

    /**
     * {@code ENS_LICENSE_MENU_VISIBLE}: whether the way to the license screen is offered in
     * the navigation (the administration page's "Manage the license" link). Only that link
     * reads it; the license banners and the license-required page link to the screen
     * whatever it says, because there it is the way out of a license problem.
     */
    @ModelAttribute("licenseMenuVisible")
    public boolean licenseMenuVisible() {
        com.winllc.certalert.security.EnsSecurityProperties properties = ens.getIfAvailable();
        return properties == null || properties.isLicenseMenuVisible();
    }
}

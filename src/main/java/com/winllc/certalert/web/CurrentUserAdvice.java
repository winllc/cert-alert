package com.winllc.certalert.web;

import com.winllc.certalert.demo.DemoAccounts;
import com.winllc.certalert.demo.DemoProperties;
import java.util.List;
import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.security.DirectoryPrincipalResolver;
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
 */
@ControllerAdvice(assignableTypes = {ViewController.class, BasicErrorController.class})
public class CurrentUserAdvice {

    private final DemoProperties demoProperties;

    public CurrentUserAdvice(DemoProperties demoProperties) {
        this.demoProperties = demoProperties;
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
        return demoProperties.isEnabled() ? DemoAccounts.ALL : List.of();
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
}

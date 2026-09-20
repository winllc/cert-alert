package com.winllc.certalert.web;

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

    @ModelAttribute("currentUser")
    public DirectoryPrincipal currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof DirectoryPrincipal principal) {
            return principal;
        }
        return null;
    }

    @ModelAttribute("isAdmin")
    public boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> DirectoryPrincipalResolver.ROLE_ADMIN.equals(authority.getAuthority()));
    }
}

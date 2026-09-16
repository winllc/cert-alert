package com.winllc.certalert.web;

import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.security.DirectoryPrincipalResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Puts the signed-in person on every page, so the bar can say who they are and how they
 * got in. Read from the context rather than injected, so it works on pages that have no
 * other reason to know about security.
 */
@ControllerAdvice(assignableTypes = ViewController.class)
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

package com.winllc.certalert.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The auditor role changes nothing: any request that would change something - anything but
 * GET, HEAD and OPTIONS - is refused (403, recorded as {@code access.denied}) for somebody
 * who holds {@link DirectoryPrincipalResolver#ROLE_AUDITOR} and not the administrator role.
 *
 * <p>One rule ahead of every other, rather than a condition on each endpoint, so an endpoint
 * added later is covered without anybody remembering to. What it lets through are the
 * requests that only read, or that are about the session rather than the data: the tables'
 * searches, which POST their query; the inventory export, which POSTs for its CSRF token;
 * verifying the audit chain, which reads it; signing in
 * and out; and agreeing to the banner.
 *
 * <p>Placed after the sign-in filters and before authorisation, so the refusal goes through
 * the same access-denied handling as any other. Not a bean, for the same reason as
 * {@link ConsentFilter}.
 */
public final class ReadOnlyRoleFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private static final RequestMatcher READS_BY_POST = new OrRequestMatcher(List.of(
            // DataTables sends its paging, ordering and search as a POST.
            PathPatternRequestMatcher.withDefaults().matcher("/api/v1/datatables/**"),
            // Checks the chain and records that it did; changes nothing in it.
            PathPatternRequestMatcher.withDefaults().matcher("/api/v1/admin/audit/verify"),
            // A person's own saved views of the inventory: a preference, not the data.
            PathPatternRequestMatcher.withDefaults().matcher("/api/v1/saved-views"),
            PathPatternRequestMatcher.withDefaults().matcher("/api/v1/saved-views/*"),
            // The inventory as a file: a POST only to carry the CSRF token and be audited; it
            // returns what an auditor may already read row by row.
            PathPatternRequestMatcher.withDefaults().matcher("/api/v1/certificates/export"),
            PathPatternRequestMatcher.withDefaults().matcher("/login"),
            PathPatternRequestMatcher.withDefaults().matcher("/logout"),
            PathPatternRequestMatcher.withDefaults().matcher(ConsentFilter.PATH)));

    /** Whether this is somebody who may read everything and change nothing. */
    public static boolean isReadOnly(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        boolean auditor = false;
        for (var authority : authentication.getAuthorities()) {
            String role = authority.getAuthority();
            if (DirectoryPrincipalResolver.ROLE_ADMIN.equals(role)) {
                return false;
            }
            auditor |= DirectoryPrincipalResolver.ROLE_AUDITOR.equals(role);
        }
        return auditor;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!SAFE.contains(request.getMethod())
                && !READS_BY_POST.matches(request)
                && isReadOnly(SecurityContextHolder.getContext().getAuthentication())) {
            throw new AccessDeniedException("An auditor can read everything and change nothing");
        }
        chain.doFilter(request, response);
    }
}

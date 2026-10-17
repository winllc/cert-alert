package com.winllc.certalert.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Holds every signed-in request to the {@link SessionPolicy}: after the sign-in filters, so
 * whichever of them signed the request in, and before anything is authorised.
 *
 * <p>A session the policy has ended is invalidated and the request sent to the sign-in page
 * ({@code /login?expired}), or answered 401 under {@code /api}. Not a bean, for the same
 * reason as {@link ConsentFilter}.
 */
public final class SessionPolicyFilter extends OncePerRequestFilter {

    /** What a page is made of: requested in parallel, and never the request somebody signs in on. */
    private static final RequestMatcher ASSETS = new OrRequestMatcher(List.of(
            PathPatternRequestMatcher.withDefaults().matcher("/css/**"),
            PathPatternRequestMatcher.withDefaults().matcher("/js/**"),
            PathPatternRequestMatcher.withDefaults().matcher("/img/**"),
            PathPatternRequestMatcher.withDefaults().matcher("/webjars/**"),
            PathPatternRequestMatcher.withDefaults().matcher("/favicon.ico")));

    private static final RequestMatcher API = PathPatternRequestMatcher.withDefaults().matcher("/api/**");

    private final SessionPolicy policy;

    public SessionPolicyFilter(SessionPolicy policy) {
        this.policy = policy;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(false);
        if (session == null
                || authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || ASSETS.matches(request)) {
            chain.doFilter(request, response);
            return;
        }
        String ended = policy.check(request, session, authentication);
        if (ended == null) {
            chain.doFilter(request, response);
            return;
        }
        policy.end(session, ended);
        SecurityContextHolder.clearContext();
        if (API.matches(request)) {
            response.sendError(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        response.sendRedirect(request.getContextPath() + "/login?expired");
    }
}

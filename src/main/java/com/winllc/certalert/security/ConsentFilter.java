package com.winllc.certalert.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Holds every request at the notice-and-consent banner until this browser session has
 * agreed to it.
 *
 * <p>Placed ahead of every sign-in filter, so a client certificate, a password and anything
 * else that signs somebody in never runs for a session that has not agreed: the request is
 * sent to the banner instead (an API call gets a 401). What it lets through without agreement
 * is the banner and its form, what the page is made of, the error page, and the machine
 * endpoints that are not browser sessions: the health probes and metrics. The SIEM pull API
 * has a chain of its own, authenticated by bearer token, and never reaches this.
 *
 * <p>Not a bean: Spring Boot registers every Filter bean with the container as well, and this
 * belongs inside the security chain only.
 */
public final class ConsentFilter extends OncePerRequestFilter {

    /** The banner, and where its "I agree" posts. */
    public static final String PATH = "/consent";

    private static final RequestMatcher EXEMPT = new OrRequestMatcher(List.of(
            PathPatternRequestMatcher.withDefaults().matcher(PATH),
            PathPatternRequestMatcher.withDefaults().matcher("/css/**"),
            PathPatternRequestMatcher.withDefaults().matcher("/js/**"),
            PathPatternRequestMatcher.withDefaults().matcher("/img/**"),
            PathPatternRequestMatcher.withDefaults().matcher("/webjars/**"),
            PathPatternRequestMatcher.withDefaults().matcher("/favicon.ico"),
            PathPatternRequestMatcher.withDefaults().matcher("/error"),
            PathPatternRequestMatcher.withDefaults().matcher("/actuator/health"),
            PathPatternRequestMatcher.withDefaults().matcher("/actuator/health/**"),
            PathPatternRequestMatcher.withDefaults().matcher("/actuator/prometheus"),
            PathPatternRequestMatcher.withDefaults().matcher("/livez"),
            PathPatternRequestMatcher.withDefaults().matcher("/readyz")));

    private static final RequestMatcher API = PathPatternRequestMatcher.withDefaults().matcher("/api/**");

    private final ConsentBanner banner;

    public ConsentFilter(ConsentBanner banner) {
        this.banner = banner;
    }

    /** Whether this request's session has agreed to the banner. */
    public static boolean acknowledged(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute(ConsentBanner.ACKNOWLEDGED));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!banner.isEnabled() || acknowledged(request) || EXEMPT.matches(request)) {
            chain.doFilter(request, response);
            return;
        }
        if (API.matches(request)) {
            response.sendError(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        response.sendRedirect(request.getContextPath() + PATH);
    }
}

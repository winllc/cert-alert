package com.winllc.certalert.api;

import com.winllc.certalert.audit.AccessDenials;
import com.winllc.certalert.audit.AuditingAccessDeniedHandler;
import com.winllc.certalert.demo.DemoMode;
import com.winllc.certalert.security.EnsSecurityProperties;
import com.winllc.certalert.security.SecurityHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The public API, for a request that carries a token: {@code /api/v1/**} with
 * {@code Authorization: Bearer cat_...}.
 *
 * <p>A filter chain of its own, ahead of the browser's. It keeps no session, reads none - a
 * request with a token and a session cookie is the token's, and only the token's - and has
 * no CSRF check, because nothing a browser sends on its own carries an {@code Authorization}
 * header: CSRF protection is lifted for exactly the requests a token authenticates, and the
 * same endpoints called with a session cookie still need the CSRF token.
 *
 * <p>Authorised by scope, endpoint by endpoint ({@link ApiEndpoints}); anything not listed is
 * refused. A token that is unknown, revoked or expired, or whose creator no longer holds the
 * role its scopes need, gets 401; a token over its rate gets 429 with {@code Retry-After}; a
 * scope it does not have, 403.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "cert-alert.security", name = "enabled", matchIfMissing = true)
@Conditional(DemoMode.Off.class)
public class ApiSecurityConfig {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    /** {@code /api/v1/**} with a bearer token: the requests this chain is for. */
    static final RequestMatcher TOKEN_REQUESTS = request -> {
        String header = request.getHeader("Authorization");
        return header != null
                && header.regionMatches(true, 0, "Bearer ", 0, 7)
                && PathPatternRequestMatcher.withDefaults().matcher("/api/v1/**").matches(request);
    };

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    public SecurityFilterChain apiTokenFilterChain(
            HttpSecurity http,
            ApiTokens tokens,
            ApiRateLimiter limiter,
            AccessDenials denials,
            EnsSecurityProperties ens) throws Exception {
        SecurityHeaders.apply(http, ens.getCsp().isReportOnly());
        http.securityMatcher(TOKEN_REQUESTS)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .securityContext(context -> context.requireExplicitSave(true))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .anonymous(anonymous -> anonymous.disable())
                .addFilterBefore(new TokenFilter(tokens, limiter), AuthorizationFilter.class)
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(PathPatternRequestMatcher.withDefaults()
                                    .matcher(org.springframework.http.HttpMethod.GET, OpenApiController.PATH))
                            .permitAll();
                    for (ApiEndpoints.Rule rule : ApiEndpoints.RULES) {
                        authorize.requestMatchers(PathPatternRequestMatcher.withDefaults()
                                        .matcher(rule.method(), rule.pattern()))
                                .hasAuthority(rule.scope().authority());
                    }
                    authorize.anyRequest().denyAll();
                })
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, failure) ->
                                problem(response, HttpStatus.UNAUTHORIZED, "invalid_token", "A valid API token is required"))
                        .accessDeniedHandler(new AuditingAccessDeniedHandler((request, response, denied) ->
                                problem(response, HttpStatus.FORBIDDEN, "insufficient_scope",
                                        "This token's scopes do not cover " + request.getMethod() + " on this endpoint"),
                                denials)));
        return http.build();
    }

    /** RFC 6750's answer, with a problem-detail body a client can show. */
    static void problem(HttpServletResponse response, HttpStatus status, String error, String detail) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setHeader("WWW-Authenticate", "Bearer realm=\"cert-alert\", error=\"" + error + "\"");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase()
                + "\",\"status\":" + status.value() + ",\"detail\":\"" + detail.replace("\"", "'") + "\"}");
    }

    /**
     * Turns the bearer token into the request's authentication, or answers the request
     * itself: 401 for a token that is not a live one, 429 for one over its rate. Clears the
     * context afterwards; nothing about the token outlives the request.
     */
    static final class TokenFilter extends OncePerRequestFilter {

        private final ApiTokens tokens;
        private final ApiRateLimiter limiter;

        TokenFilter(ApiTokens tokens, ApiRateLimiter limiter) {
            this.tokens = tokens;
            this.limiter = limiter;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            String bearer = header == null || header.length() < 7 ? "" : header.substring(7).trim();
            ApiTokens.Outcome outcome = tokens.authenticate(bearer, request.getRemoteAddr());
            if (!outcome.accepted()) {
                problem(response, HttpStatus.UNAUTHORIZED, "invalid_token", describe(outcome.refusal()));
                return;
            }
            ApiTokenAuthentication authentication = outcome.authentication();
            long wait = limiter.acquire(authentication.tokenId());
            if (wait > 0) {
                response.setHeader("Retry-After", String.valueOf(wait));
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                        + "\"detail\":\"This token has used its allowance; try again in " + wait + " s\"}");
                return;
            }
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            String method = request.getMethod().toUpperCase(Locale.ROOT);
            String path = request.getRequestURI();
            try {
                tokens.recordUse(authentication, method, path);
                chain.doFilter(request, response);
            } finally {
                if (!SAFE.contains(method)) {
                    tokens.recordWrite(authentication, method, path, response.getStatus());
                }
                SecurityContextHolder.clearContext();
            }
        }

        private static String describe(ApiTokens.Refusal refusal) {
            return switch (refusal) {
                case REVOKED -> "This API token has been revoked";
                case EXPIRED -> "This API token has expired";
                case CREATOR_ROLE_GONE -> "The person who created this API token no longer holds a role its scopes need";
                case CREATOR_GONE -> "The person who created this API token is no longer in the directory";
                case CREATOR_DISABLED -> "The account of the person who created this API token is disabled or locked";
                case DIRECTORY_UNAVAILABLE -> "The directory cannot be read to check this API token's creator; try again";
                case DISABLED -> "API tokens are turned off on this installation";
                case UNKNOWN -> "A valid API token is required";
            };
        }
    }
}

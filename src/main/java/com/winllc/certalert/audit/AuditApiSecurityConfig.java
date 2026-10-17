package com.winllc.certalert.audit;

import com.winllc.certalert.security.EnsSecurityProperties;
import com.winllc.certalert.security.SecurityHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The SIEM pull API's own filter chain, ahead of every other: {@code /api/audit/**} is
 * authenticated by {@code Authorization: Bearer ensat_...} alone - no session, no CSRF, no
 * sign-in page - and answers 401 to anything else. The same in a demo as anywhere, and the
 * license interceptor lets it through whatever the license says: the audit trail has to stay
 * readable.
 */
@Configuration(proxyBeanMethods = false)
public class AuditApiSecurityConfig {

    public static final String PATH = "/api/audit/**";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain auditApiFilterChain(
            HttpSecurity http, AuditTokens tokens, EnsSecurityProperties ens) throws Exception {
        SecurityHeaders.apply(http, ens.getCsp().isReportOnly());
        http.securityMatcher(PATH)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .securityContext(context -> context.requireExplicitSave(true))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .anonymous(anonymous -> anonymous.disable())
                .addFilterBefore(new BearerTokenFilter(tokens), AuthorizationFilter.class)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().hasAuthority(AuditTokenAuthentication.AUTHORITY))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                        .accessDeniedHandler((request, response, denied) ->
                                response.sendError(HttpStatus.UNAUTHORIZED.value())));
        return http.build();
    }

    /** Turns a valid bearer token into the request's authentication; anything else stays anonymous. */
    static final class BearerTokenFilter extends OncePerRequestFilter {

        private final AuditTokens tokens;

        BearerTokenFilter(AuditTokens tokens) {
            this.tokens = tokens;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader("Authorization");
            if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                tokens.authenticate(header.substring(7).trim()).ifPresent(token -> {
                    SecurityContext context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(new AuditTokenAuthentication(token.getId(), token.getName()));
                    SecurityContextHolder.setContext(context);
                });
            }
            try {
                chain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }
}

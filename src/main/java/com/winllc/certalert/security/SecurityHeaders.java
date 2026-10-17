package com.winllc.certalert.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * The response headers of the ENS platform baseline, the same on every filter chain here:
 * the ordinary one, the demo's and the SIEM pull API's.
 *
 * <p>The policy allows scripts from this application and nowhere else - no inline script, no
 * {@code eval}, no event-handler attributes - which is what makes an injected
 * {@code <script>} or {@code onerror=} inert even where escaping has been got wrong. Styles
 * may be inline: the classification banners are written into a style element, and the
 * libraries set styles from script. Images and fonts may be {@code data:} URIs, which
 * Tabler's stylesheet uses for its icons. Nothing may frame these pages.
 *
 * <p>HSTS goes on secure requests only - TLS straight to the application, or behind a proxy
 * that says so when {@code server.forward-headers-strategy} is set - because on a plain HTTP
 * response a browser ignores it anyway.
 */
public final class SecurityHeaders {

    /** Exactly as the ENS baseline specifies it. */
    public static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; "
            + "connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; "
            + "object-src 'none'";

    public static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=()";

    private SecurityHeaders() {}

    /**
     * @param reportOnly {@code ENS_CSP_REPORT_ONLY}: report what the policy would block
     *     rather than block it
     */
    public static void apply(HttpSecurity http, boolean reportOnly) throws Exception {
        http.headers(headers -> headers
                .contentSecurityPolicy(csp -> {
                    csp.policyDirectives(CONTENT_SECURITY_POLICY);
                    if (reportOnly) {
                        csp.reportOnly();
                    }
                })
                .httpStrictTransportSecurity(hsts -> hsts.maxAgeInSeconds(31_536_000).includeSubDomains(true))
                .contentTypeOptions(contentType -> {})
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(referrer ->
                        referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .permissionsPolicyHeader(permissions -> permissions.policy(PERMISSIONS_POLICY)));
    }
}

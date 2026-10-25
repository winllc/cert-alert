package com.winllc.certalert.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.winllc.certalert.audit.AccessDenials;
import com.winllc.certalert.audit.AuditingAccessDeniedHandler;
import com.winllc.certalert.demo.DemoMode;
import com.winllc.certalert.observability.ManagementEndpoints;
import com.winllc.certalert.security.sso.SsoSignIn;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.DefaultAuthenticationEventPublisher;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationFailureServiceExceptionEvent;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.ExceptionTranslationFilter;
import org.springframework.security.web.access.WebInvocationPrivilegeEvaluator;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.preauth.x509.SubjectX500PrincipalExtractor;
import org.springframework.security.web.authentication.preauth.x509.X509AuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Two ways in, in order of preference.
 *
 * <p><b>A client certificate.</b> Everyone in this directory already holds one, and the
 * application already caches every certificate the directory publishes, so a presented
 * certificate is recognised by its fingerprint rather than by a name. Nothing to type, and
 * access follows the directory: remove someone's certificate there and they stop getting
 * in.
 *
 * <p><b>A directory password.</b> For a browser that presents no certificate. It binds to
 * the same directory as the person signing in, so no password is ever stored here, and the
 * roles come out of the same cache either way.
 *
 * <p>This only works if the connector asks for a certificate but does not insist on one:
 * see {@code server.ssl.client-auth: want} in the README. With {@code need}, a browser
 * without a certificate cannot reach the login form at all.
 */
@Configuration
@EnableWebSecurity
@ConditionalOnProperty(prefix = "cert-alert.security", name = "enabled", matchIfMissing = true)
// The demo has a policy of its own - no sign-in, and nothing writable - and the two are
// never both in force: see DemoSecurityConfig.
@Conditional(DemoMode.Off.class)
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
        // The images among them: the sign-in page carries the logo, and nobody has signed
        // in when it is served. The notice-and-consent banner comes before signing in too.
        "/login", ConsentFilter.PATH, "/css/**", "/js/**", "/img/**", "/webjars/**", "/favicon.ico", "/error",
        // The API's OpenAPI document: it describes the product, not anybody's data.
        com.winllc.certalert.api.OpenApiController.PATH
    };

    /** Administrators, or anyone signed in, depending on how the application is configured. */
    private static AuthorizationManager<RequestAuthorizationContext> contactEditors(SecurityProperties properties) {
        return properties.getContactEditors() == SecurityProperties.ContactEditors.AUTHENTICATED
                ? AuthenticatedAuthorizationManager.authenticated()
                : AuthorityAuthorizationManager.hasRole("ADMIN");
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            SecurityProperties properties,
            X509DirectoryUserDetailsService x509UserDetailsService,
            List<AuthenticationProvider> authenticationProviders,
            AccessDenials denials,
            EnsSecurityProperties ens,
            ConsentBanner consentBanner,
            SessionPolicy sessionPolicy,
            EnsX509Properties x509,
            ForwardedCertificates forwardedCertificates,
            AuthenticationEventPublisher authenticationEvents,
            Environment environment,
            SsoSignIn sso,
            ObjectProvider<WebInvocationPrivilegeEvaluator> privileges)
            throws Exception {

        SecurityHeaders.apply(http, ens.getCsp().isReportOnly());
        // Ahead of every sign-in filter - including those that send a browser off to a
        // single sign-on provider - so nothing signs anybody in before they have agreed.
        http.addFilterBefore(new ConsentFilter(consentBanner), LogoutFilter.class);
        // Then where the certificate comes from: the TLS handshake, or a trusted proxy's header.
        http.addFilterBefore(
                new ClientCertificateHeaderFilter(x509, forwardedCertificates, authenticationEvents),
                X509AuthenticationFilter.class);
        // After every sign-in filter, before anything is authorised: idle, absolute and
        // concurrent limits, the same whichever way somebody signed in.
        http.addFilterBefore(new SessionPolicyFilter(sessionPolicy), ExceptionTranslationFilter.class);
        // Then, ahead of every rule below: an auditor changes nothing, whatever a rule says.
        http.addFilterAfter(new ReadOnlyRoleFilter(), ExceptionTranslationFilter.class);

        boolean singleSignOn = !sso.options().isEmpty();
        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(PUBLIC_PATHS)
                .permitAll()
                // Single sign-on's own endpoints, where it is configured: starting it, the
                // provider's answer, and this service provider's SAML metadata.
                .requestMatchers(singleSignOn ? SsoSignIn.PUBLIC_PATHS : new String[] {"/__no-single-sign-on"})
                .permitAll()
                // Liveness has to answer before anyone signs in; everything else the
                // management endpoints expose is for administrators.
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/livez", "/readyz")
                .permitAll()
                // Metrics, on the management port only, for the scraper - the port the
                // ingress never publishes.
                .requestMatchers(ManagementEndpoints.prometheusScrape(environment))
                .permitAll()
                .requestMatchers("/actuator/**")
                .hasRole("ADMIN")
                // The administration page reads the whole audit trail: who has been here,
                // what they touched and when. The endpoint behind it checks the same thing
                // for itself, because a table is asked for directly as often as it is
                // reached through the page it sits on. Auditors read it too; what they may
                // not do is change anything (ReadOnlyRoleFilter, and the rules below).
                .requestMatchers(HttpMethod.GET, "/admin", "/api/v1/audit/summary", "/admin/integrations")
                .hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers("/admin", "/api/v1/audit/summary", "/admin/integrations")
                .hasRole("ADMIN")
                // The audit log page: the whole trail, its export, verification, delivery
                // status and the SIEM tokens. Its API is under /api/v1/admin, below. The SIEM
                // pull API has a chain of its own (AuditApiSecurityConfig), authenticated by
                // bearer token.
                .requestMatchers(HttpMethod.GET, "/admin/audit", "/admin/audit/**")
                .hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers("/admin/audit", "/admin/audit/**")
                .hasRole("ADMIN")
                // The license screen: what is installed, this installation's id, and the
                // upload. Reachable whatever the license says. Installing is an
                // administrator's; an auditor may read it.
                .requestMatchers(HttpMethod.GET, "/admin/license", "/admin/license/**")
                .hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers("/admin/license", "/admin/license/**")
                .hasRole("ADMIN")
                // Defining what this deployment keeps about its servers adds a field to
                // every server at once, and retiring one takes every value with it; setting
                // a value is the same decision made about one server. Reading either is
                // ordinary: they are attributes of a server, shown with the rest of it.
                // Verifying the audit chain reads it, and is an auditor's to do as well.
                // Jobs started by hand: a run's result can name recipients and carry the
                // mail server's or directory's own errors, so it is for the people who start
                // them, and auditors.
                .requestMatchers("/api/v1/jobs", "/api/v1/jobs/**")
                .hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/**")
                .hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers(HttpMethod.POST, "/api/v1/admin/audit/verify")
                .hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers("/api/v1/admin/**")
                .hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/servers/*/attributes/**")
                .hasRole("ADMIN")
                // Triggering a sweep of the whole directory, and above all pruning it, is
                // not something a reader gets to do.
                .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/sync/**",
                        "/api/v1/changelog/**",
                        "/api/v1/revocation/check",
                        "/api/v1/notifications/digest")
                .hasRole("ADMIN")
                // How far ahead the round-up looks decides what every point of contact in
                // the directory is written to about, so it is not in the same bracket as
                // editing one server's contacts. Reading it stays open to anyone signed in.
                .requestMatchers(HttpMethod.PUT, "/api/v1/notifications/settings")
                .hasRole("ADMIN")
                // A server's points of contact are managed by the people associated with
                // that server - its contacts, and the members of a project it belongs to -
                // as well as by administrators. That is a question about one server rather
                // than about the application, so the rule is ServerAccessPolicy's and all
                // this asks for is somebody signed in to ask it about.
                .requestMatchers(
                        HttpMethod.POST, "/api/v1/servers/*/contacts", "/api/v1/servers/*/contacts/**")
                .authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/v1/servers/*/contacts/**")
                .authenticated()
                // The addresses a person answers to are a different matter: they bind that
                // person to every server named after one, so they stay with
                // cert-alert.security.contact-editors.
                .requestMatchers(
                        HttpMethod.POST, "/api/v1/users/*/addresses", "/api/v1/users/*/addresses/**")
                .access(contactEditors(properties))
                .requestMatchers(HttpMethod.DELETE, "/api/v1/users/*/addresses/**")
                .access(contactEditors(properties))
                // Who runs a project is not curation: it hands over the contact lists of
                // every server in the project, and the notices about their certificates.
                .requestMatchers(
                        HttpMethod.POST, "/api/v1/projects/*/admins/**")
                .hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/projects/*/admins/**")
                .hasRole("ADMIN")
                // Projects are the same kind of thing: data this application keeps
                // alongside the directory, curated by the same people.
                .requestMatchers(HttpMethod.POST, "/api/v1/projects", "/api/v1/projects/**")
                .access(contactEditors(properties))
                .requestMatchers(HttpMethod.PUT, "/api/v1/projects/**")
                .access(contactEditors(properties))
                .requestMatchers(HttpMethod.DELETE, "/api/v1/projects/**")
                .access(contactEditors(properties))
                .anyRequest()
                .authenticated());

        if (properties.getX509().isEnabled()) {
            http.x509(certificates -> certificates
                    // Names the principal for logs; the real resolution is by fingerprint.
                    .x509PrincipalExtractor(new SubjectX500PrincipalExtractor())
                    // Keeps the whole chain, which the revocation check needs for the issuer.
                    .authenticationDetailsSource(ClientCertificateDetails::new)
                    .authenticationUserDetailsService(x509UserDetailsService));
        }

        authenticationProviders.forEach(http::authenticationProvider);

        // OpenID Connect and SAML, each only where it is configured.
        sso.apply(http, authenticationEvents);

        // Not back to a page the person signing in may not read (see SignInSuccessHandler).
        http.formLogin(form -> form.loginPage("/login")
                        .successHandler(new SignInSuccessHandler(privileges))
                        .permitAll())
                .logout(logout -> logout
                        .addLogoutHandler(sessionPolicy::loggingOut)
                        .logoutSuccessUrl("/login?logout")
                        .permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint())
                        // Every 403 the chain answers is recorded as access.denied.
                        .accessDeniedHandler(new AuditingAccessDeniedHandler(new AccessDeniedHandlerImpl(), denials)));

        return http.build();
    }

    /**
     * Spring Boot's publisher, plus the failures it would otherwise not publish.
     *
     * <p>It maps exceptions to failure events by exact class, and has no event for one it
     * does not know - so a revoked or expired certificate being refused would leave no
     * {@code auth.login} failure in the audit trail at all, and neither would a password
     * sign-in the directory could not be reached to check.
     */
    @Bean
    public DefaultAuthenticationEventPublisher authenticationEventPublisher(ApplicationEventPublisher publisher) {
        DefaultAuthenticationEventPublisher events = new DefaultAuthenticationEventPublisher(publisher);
        events.setAdditionalExceptionMappings(Map.of(
                RefusedCertificateException.class, AuthenticationFailureBadCredentialsEvent.class,
                InternalAuthenticationServiceException.class, AuthenticationFailureServiceExceptionEvent.class,
                // A single sign-on refused: a token or response that did not check out, or
                // a person the directory does not hold.
                OAuth2AuthenticationException.class, AuthenticationFailureBadCredentialsEvent.class,
                Saml2AuthenticationException.class, AuthenticationFailureBadCredentialsEvent.class));
        return events;
    }

    /**
     * An unauthenticated API call gets a 401; anything else gets the login form.
     *
     * <p>Spelled out rather than left to {@code defaultAuthenticationEntryPointFor}, which
     * applies a lone mapping to every request regardless of its matcher - so adding the API
     * rule alone would send a browser a bare 401 instead of somewhere to sign in.
     */
    private AuthenticationEntryPoint authenticationEntryPoint() {
        LinkedHashMap<RequestMatcher, AuthenticationEntryPoint> mappings = new LinkedHashMap<>();
        mappings.put(
                PathPatternRequestMatcher.withDefaults().matcher("/api/**"),
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED));

        DelegatingAuthenticationEntryPoint entryPoint = new DelegatingAuthenticationEntryPoint(mappings);
        entryPoint.setDefaultEntryPoint(new LoginUrlAuthenticationEntryPoint("/login"));
        return entryPoint;
    }

}

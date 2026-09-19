package com.winllc.certalert.security;

import java.util.LinkedHashMap;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authorization.AuthenticatedAuthorizationManager;
import org.springframework.security.authorization.AuthorityAuthorizationManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.DelegatingAuthenticationEntryPoint;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.preauth.x509.SubjectX500PrincipalExtractor;
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
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
        // The images among them: the sign-in page carries the logo, and nobody has signed
        // in when it is served.
        "/login", "/css/**", "/js/**", "/img/**", "/webjars/**", "/favicon.ico", "/error"
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
            List<AuthenticationProvider> authenticationProviders)
            throws Exception {

        http.authorizeHttpRequests(authorize -> authorize
                .requestMatchers(PUBLIC_PATHS)
                .permitAll()
                // Liveness has to answer before anyone signs in; everything else the
                // management endpoints expose is for administrators.
                .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info")
                .permitAll()
                .requestMatchers("/actuator/**")
                .hasRole("ADMIN")
                // The administration page reads the whole audit trail: who has been here,
                // what they touched and when. The endpoint behind it checks the same thing
                // for itself, because a table is asked for directly as often as it is
                // reached through the page it sits on.
                .requestMatchers("/admin", "/api/v1/audit/summary")
                .hasRole("ADMIN")
                // Defining what this deployment keeps about its servers adds a field to
                // every server at once, and retiring one takes every value with it; setting
                // a value is the same decision made about one server. Reading either is
                // ordinary: they are attributes of a server, shown with the rest of it.
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
            http.x509(x509 -> x509
                    // Names the principal for logs; the real resolution is by fingerprint.
                    .x509PrincipalExtractor(new SubjectX500PrincipalExtractor())
                    .authenticationUserDetailsService(x509UserDetailsService));
        }

        authenticationProviders.forEach(http::authenticationProvider);

        http.formLogin(form -> form.loginPage("/login").permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login?logout").permitAll())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(authenticationEntryPoint()));

        return http.build();
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

    /**
     * Password authentication by binding to the directory as the person signing in.
     *
     * <p>Only registered when it is enabled, so a deployment that wants certificates and
     * nothing else can say so and be certain there is no password path at all.
     */
    @Bean
    @ConditionalOnProperty(prefix = "cert-alert.security.ldap", name = "enabled", matchIfMissing = true)
    public AuthenticationProvider ldapAuthenticationProvider(
            BaseLdapPathContextSource contextSource,
            SecurityProperties properties,
            DirectoryUserDetailsContextMapper contextMapper) {

        SecurityProperties.Ldap ldap = properties.getLdap();
        BindAuthenticator authenticator = new BindAuthenticator(contextSource);
        if (!ldap.getUserDnPatterns().isEmpty()) {
            // Bind straight to a known DN shape, for directories that allow no search
            // before authenticating.
            authenticator.setUserDnPatterns(ldap.getUserDnPatterns().toArray(String[]::new));
        } else {
            authenticator.setUserSearch(
                    new FilterBasedLdapUserSearch(ldap.getUserSearchBase(), ldap.getUserSearchFilter(), contextSource));
        }

        LdapAuthenticationProvider provider = new LdapAuthenticationProvider(authenticator);
        provider.setUserDetailsContextMapper(contextMapper);
        return provider;
    }
}

package com.winllc.certalert.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;

/**
 * How a password is checked, kept apart from which URLs are protected.
 *
 * <p>Separate from {@link SecurityConfig} because the two questions have different answers
 * in a demo. A demo has its own idea of what may be reached and by whom, so it brings its
 * own filter chain - but it checks a password exactly as any other deployment does, by
 * binding to the directory as the person signing in. Leaving this bean inside the ordinary
 * chain's configuration, which a demo switches off wholesale, would have left a demo with
 * a sign-in page and nothing behind it to answer.
 */
@Configuration
public class DirectoryAuthenticationConfig {

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

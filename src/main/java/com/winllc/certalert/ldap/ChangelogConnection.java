package com.winllc.certalert.ldap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.ldap.autoconfigure.LdapConnectionDetails;
import org.springframework.boot.ldap.autoconfigure.LdapProperties;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.stereotype.Component;

/**
 * A second connection to the same directory, rooted at nothing.
 *
 * <p>The changelog is a suffix of its own - {@code cn=changelog} - and the change-number
 * bounds live on the root DSE. Neither is under {@code spring.ldap.base}, and the shared
 * context source resolves every name relative to that base, so asking it for
 * {@code cn=changelog} would look under the data tree and find nothing.
 *
 * <p>The context source is built here rather than exposed as a bean on purpose. Spring Boot
 * creates the application's own {@code LdapContextSource} only when no other
 * {@code ContextSource} bean exists, so publishing this one would silently take its place -
 * and the sweep would start looking for {@code ou=people} at the root.
 */
@Component
@ConditionalOnProperty(prefix = "cert-alert.ldap.changelog", name = "enabled", havingValue = "true")
public class ChangelogConnection {

    private final LdapTemplate template;

    public ChangelogConnection(LdapConnectionDetails connectionDetails, LdapProperties properties) {
        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrls(connectionDetails.getUrls());
        // Empty on purpose: see the class javadoc.
        contextSource.setBase("");
        if (connectionDetails.getUsername() != null && !connectionDetails.getUsername().isBlank()) {
            contextSource.setUserDn(connectionDetails.getUsername());
            contextSource.setPassword(connectionDetails.getPassword());
        } else {
            contextSource.setAnonymousReadOnly(true);
        }
        contextSource.setBaseEnvironmentProperties(baseEnvironment(properties));
        contextSource.afterPropertiesSet();

        this.template = new LdapTemplate(contextSource);
        // An entry named by the changelog may already have been deleted by the time we look;
        // that is an ordinary outcome here, not an error.
        this.template.setIgnorePartialResultException(true);
        this.template.setIgnoreNameNotFoundException(true);
    }

    /**
     * The JNDI properties configured under {@code spring.ldap.base-environment}, which
     * Boot applies to the application's own context source and which this one would
     * otherwise miss. {@code java.naming.ldap.attributes.binary} is the reason it matters
     * here: it is how a certificate attribute under a name of your own is declared binary,
     * and this connection is one of the two that read certificates.
     */
    private static Map<String, Object> baseEnvironment(LdapProperties properties) {
        return new HashMap<>(properties.getBaseEnvironment());
    }

    /** Operations against the directory root. */
    public LdapTemplate template() {
        return template;
    }
}

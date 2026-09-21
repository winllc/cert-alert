package com.winllc.certalert.ldap;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.ldap.autoconfigure.LdapConnectionDetails;
import org.springframework.boot.ldap.autoconfigure.LdapProperties;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.stereotype.Component;

/**
 * A connection to the directory rooted at nothing, for working with one entry at a time.
 *
 * <p>A cached entry carries the distinguished name the directory gave it, which is
 * absolute. The application's own context source resolves every name under
 * {@code spring.ldap.base}, so handing it that name would look for it underneath itself -
 * {@code uid=alice,ou=people,dc=example,dc=test,dc=example,dc=test}.
 *
 * <p>The context source is built here rather than published as a bean, on purpose. Spring
 * Boot creates the application's own {@code LdapContextSource} only when no other
 * {@code ContextSource} bean exists, so publishing this one would silently take its place -
 * and the sweep would start looking for {@code ou=people} at the root.
 */
@Component
public class DirectoryEntryConnection {

    private final LdapTemplate template;
    private final boolean credentialled;

    public DirectoryEntryConnection(LdapConnectionDetails connectionDetails, LdapProperties properties) {
        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrls(connectionDetails.getUrls());
        // Empty on purpose: see the class javadoc.
        contextSource.setBase("");
        this.credentialled = connectionDetails.getUsername() != null
                && !connectionDetails.getUsername().isBlank();
        if (credentialled) {
            contextSource.setUserDn(connectionDetails.getUsername());
            contextSource.setPassword(connectionDetails.getPassword());
        }
        contextSource.setBaseEnvironmentProperties(baseEnvironment(properties));
        contextSource.afterPropertiesSet();
        this.template = new LdapTemplate(contextSource);
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

    /** Operations against absolute distinguished names. */
    public LdapTemplate template() {
        return template;
    }

    /**
     * Whether this binds as somebody. An anonymous bind can read what the directory lets
     * anonymous read and will be refused anything that writes - which is worth saying on
     * the page rather than discovering on save.
     */
    public boolean isCredentialled() {
        return credentialled;
    }
}

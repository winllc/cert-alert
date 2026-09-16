package com.winllc.certalert.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.ldap.userdetails.UserDetailsContextMapper;
import org.springframework.stereotype.Component;

/**
 * Maps a successful directory bind onto the same principal the certificate path produces.
 *
 * <p>The bind proves who they are; the roles still come from the cached directory, so a
 * person has the same authority whichever way they signed in. Someone who binds but has no
 * cached entry - a valid account not yet scraped - is let in as a plain reader rather than
 * turned away.
 */
@Component
public class DirectoryUserDetailsContextMapper implements UserDetailsContextMapper {

    private final DirectoryPrincipalResolver resolver;

    public DirectoryUserDetailsContextMapper(DirectoryPrincipalResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public UserDetails mapUserFromContext(
            DirContextOperations ctx, String username, Collection<? extends GrantedAuthority> authorities) {

        // The username they typed, plus whatever the entry itself calls them: a directory
        // that lets people bind by uid may hold their certificates under an address.
        Set<String> identifiers = new LinkedHashSet<>();
        identifiers.add(username);
        addAll(identifiers, ctx, "uid", "mail", "icEmail", "internetEmail", "niprnetEmail", "siprnetEmail", "cn");

        return resolver
                .findByAnyIdentifier(identifiers)
                .map(user -> (UserDetails) resolver.principalFor(user, DirectoryPrincipal.AuthenticationMethod.LDAP))
                .orElseGet(() -> resolver.unknownPrincipal(
                        username, identifiers, DirectoryPrincipal.AuthenticationMethod.LDAP));
    }

    private void addAll(Set<String> identifiers, DirContextOperations ctx, String... attributes) {
        for (String attribute : attributes) {
            String[] values = ctx.getStringAttributes(attribute);
            if (values != null) {
                for (String value : values) {
                    if (value != null && !value.isBlank()) {
                        identifiers.add(value);
                    }
                }
            }
        }
    }

    @Override
    public void mapUserToContext(UserDetails user, DirContextAdapter ctx) {
        throw new UnsupportedOperationException("cert-alert never writes to the directory");
    }
}

package com.winllc.certalert.security;

import com.winllc.certalert.ldap.LdapProperties;
import com.winllc.certalert.ldap.UserField;
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
 *
 * <p>The cached entry is the one at the DN they bound as, and nothing else. Looking it up
 * by what the entry calls them would find whoever else is called that: the sign-in search
 * covers the whole tree and the sweep may not, so an account the cache has never seen can
 * share a common name with one it has.
 */
@Component
public class DirectoryUserDetailsContextMapper implements UserDetailsContextMapper {

    private final DirectoryPrincipalResolver resolver;
    private final LdapProperties ldapProperties;

    public DirectoryUserDetailsContextMapper(DirectoryPrincipalResolver resolver, LdapProperties ldapProperties) {
        this.resolver = resolver;
        this.ldapProperties = ldapProperties;
    }

    @Override
    public UserDetails mapUserFromContext(
            DirContextOperations ctx, String username, Collection<? extends GrantedAuthority> authorities) {

        String dn = ctx.getNameInNamespace();
        return resolver
                .findByDn(dn)
                .map(user -> (UserDetails) resolver.principalFor(user, DirectoryPrincipal.AuthenticationMethod.LDAP))
                .orElseGet(() -> resolver.unknownPrincipal(
                        username, dn, uidsOf(ctx), DirectoryPrincipal.AuthenticationMethod.LDAP));
    }

    /**
     * What the bound entry itself publishes as its uid, for deciding the roles of somebody
     * the cache does not hold yet - the same attribute a sweep would cache it from. Never
     * its addresses, which a directory may let the person set themselves, nor a common or
     * display name.
     */
    private Set<String> uidsOf(DirContextOperations ctx) {
        Set<String> uids = new LinkedHashSet<>();
        addAll(uids, ctx, UserField.UID.attributeName(ldapProperties.getUser()));
        return uids;
    }

    private void addAll(Set<String> identifiers, DirContextOperations ctx, String attribute) {
        if (attribute == null || attribute.isBlank()) {
            return;
        }
        String[] values = ctx.getStringAttributes(attribute.trim());
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    identifiers.add(value);
                }
            }
        }
    }

    @Override
    public void mapUserToContext(UserDetails user, DirContextAdapter ctx) {
        throw new UnsupportedOperationException("cert-alert never writes to the directory");
    }
}

package com.winllc.certalert.security;

import com.winllc.certalert.domain.EmailAddresses;
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
                        username, dn, identifiersOf(ctx), DirectoryPrincipal.AuthenticationMethod.LDAP));
    }

    /**
     * What the bound entry itself publishes as its uid and its addresses, for deciding the
     * roles of somebody the cache does not hold yet: a directory that lets people bind by
     * uid may name its administrators by address. The same attributes a sweep would cache
     * them under, and never a common or display name.
     */
    private Set<String> identifiersOf(DirContextOperations ctx) {
        LdapProperties.User mapping = ldapProperties.getUser();
        Set<String> identifiers = new LinkedHashSet<>();
        for (UserField field : new UserField[] {
            UserField.UID, UserField.MAIL, UserField.IC_EMAIL,
            UserField.INTERNET_EMAIL, UserField.NIPRNET_EMAIL, UserField.SIPRNET_EMAIL
        }) {
            addAll(identifiers, ctx, field.attributeName(mapping));
        }
        // Read the way the sweep reads them: split, and only what is an address.
        for (String attribute : mapping.getAdditionalEmailAttributes()) {
            Set<String> values = new LinkedHashSet<>();
            addAll(values, ctx, attribute);
            values.stream()
                    .flatMap(value -> EmailAddresses.split(value).stream())
                    .filter(EmailAddresses::isAddress)
                    .forEach(identifiers::add);
        }
        return identifiers;
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

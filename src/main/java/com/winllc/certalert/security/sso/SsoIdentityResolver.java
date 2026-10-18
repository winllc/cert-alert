package com.winllc.certalert.security.sso;

import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.ldap.LdapProperties;
import com.winllc.certalert.ldap.UserField;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.security.DirectoryPrincipalResolver;
import com.winllc.certalert.security.SecurityProperties;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.ldap.support.LdapEncoder;
import org.springframework.ldap.support.LdapUtils;
import org.springframework.security.ldap.SpringSecurityLdapTemplate;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who an identity provider has signed in, as a person in the directory.
 *
 * <p>The provider names them by their uid or by the DN of their entry ({@code match}), and
 * that is looked up exactly as a directory-password sign-in finds the entry it binds as: in
 * the cached copy of the directory first, and failing that in the directory itself - under
 * {@code cert-alert.security.ldap.user-search-base} for a uid (or by
 * {@code user-dn-patterns}, where those are how this directory is reached), or at the DN.
 * Never by an address and never by a name: an identity provider's {@code email} or
 * {@code name} claim is something its holder or the provider's administrators can often
 * set, and a directory is no help in telling two people of one name apart.
 *
 * <p>A uid the cache holds for more than one person, or a search that finds more than one
 * entry, resolves to nobody. So does a DN outside the directory's base.
 */
@Component
public class SsoIdentityResolver {

    private static final Logger log = LoggerFactory.getLogger(SsoIdentityResolver.class);

    private final DirectoryPrincipalResolver resolver;
    private final DirectoryUserRepository users;
    private final BaseLdapPathContextSource contextSource;
    private final SecurityProperties security;
    private final LdapProperties ldap;

    public SsoIdentityResolver(
            DirectoryPrincipalResolver resolver,
            DirectoryUserRepository users,
            BaseLdapPathContextSource contextSource,
            SecurityProperties security,
            LdapProperties ldap) {
        this.resolver = resolver;
        this.users = users;
        this.contextSource = contextSource;
        this.security = security;
        this.ldap = ldap;
    }

    /**
     * @param name the uid or DN the provider gave
     * @param groups the groups it says they are in (they count only where configured)
     * @return the person, with their roles; empty where the directory does not hold them
     */
    @Transactional(readOnly = true)
    public Optional<DirectoryPrincipal> resolve(
            String name,
            SsoProperties.Match match,
            Collection<String> groups,
            DirectoryPrincipal.AuthenticationMethod method) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String value = name.trim();
        Optional<DirectoryUser> cached = match == SsoProperties.Match.DN ? resolver.findByDn(value) : cachedByUid(value);
        if (cached.isPresent()) {
            return Optional.of(resolver.principalFor(cached.get(), method, groups));
        }
        Optional<DirContextOperations> entry;
        try {
            entry = match == SsoProperties.Match.DN ? entryAt(value) : entryForUid(value);
        } catch (RuntimeException e) {
            log.warn("Could not look {} up in the directory for a single sign-on: {}", value, e.toString());
            return Optional.empty();
        }
        return entry.map(found -> {
            String dn = found.getNameInNamespace();
            // Swept since it was searched for, or reached by a DN spelled differently.
            Optional<DirectoryUser> byDn = resolver.findByDn(dn);
            if (byDn.isPresent()) {
                return resolver.principalFor(byDn.get(), method, groups);
            }
            return resolver.unknownPrincipal(value, dn, uidsOf(found), method, groups);
        });
    }

    /** The one cached person whose uid this is - their uid, not an address or a name. */
    private Optional<DirectoryUser> cachedByUid(String uid) {
        List<DirectoryUser> named = users.findByIdentifier(uid.toLowerCase(Locale.ROOT)).stream()
                .filter(user -> user.getUid() != null && user.getUid().equalsIgnoreCase(uid))
                .toList();
        if (named.size() > 1) {
            log.warn("uid {} names {} cached directory entries; matching none of them", uid, named.size());
        }
        return named.size() == 1 ? Optional.of(named.getFirst()) : Optional.empty();
    }

    /** The entry with this uid in the directory itself, found as a password sign-in finds it. */
    private Optional<DirContextOperations> entryForUid(String uid) {
        SecurityProperties.Ldap signIn = security.getLdap();
        String attribute = UserField.UID.attributeName(ldap.getUser());
        if (!signIn.getUserDnPatterns().isEmpty()) {
            for (String pattern : signIn.getUserDnPatterns()) {
                String dn = MessageFormat.format(pattern, LdapEncoder.nameEncode(uid));
                Optional<DirContextOperations> entry = lookup(dn);
                if (entry.isPresent() && uidsOf(entry.get()).stream().anyMatch(uid::equalsIgnoreCase)) {
                    return entry;
                }
            }
            return Optional.empty();
        }
        // The search filter is built here, on the uid attribute alone, rather than taken from
        // user-search-filter, which a deployment may have widened to match on mail too.
        FilterBasedLdapUserSearch search = new FilterBasedLdapUserSearch(
                signIn.getUserSearchBase(), "(" + attribute + "={0})", contextSource);
        try {
            return Optional.of(search.searchForUser(uid));
        } catch (org.springframework.security.core.userdetails.UsernameNotFoundException
                | org.springframework.dao.IncorrectResultSizeDataAccessException e) {
            return Optional.empty();
        }
    }

    /** The entry at this DN, where it is under the directory's base. */
    private Optional<DirContextOperations> entryAt(String dn) {
        LdapName name;
        try {
            name = new LdapName(dn);
        } catch (InvalidNameException e) {
            return Optional.empty();
        }
        LdapName base = contextSource.getBaseLdapName();
        if (!base.isEmpty() && !name.startsWith(base)) {
            return Optional.empty();
        }
        return lookup(LdapUtils.removeFirst(name, base).toString());
    }

    private Optional<DirContextOperations> lookup(String relativeDn) {
        try {
            return Optional.of(new SpringSecurityLdapTemplate(contextSource).retrieveEntry(relativeDn, null));
        } catch (org.springframework.ldap.NameNotFoundException e) {
            return Optional.empty();
        }
    }

    private Set<String> uidsOf(DirContextOperations entry) {
        Set<String> uids = new LinkedHashSet<>();
        String[] values = entry.getStringAttributes(UserField.UID.attributeName(ldap.getUser()));
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    uids.add(value);
                }
            }
        }
        return uids;
    }
}

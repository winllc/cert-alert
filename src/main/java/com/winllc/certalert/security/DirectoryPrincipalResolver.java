package com.winllc.certalert.security;

import com.winllc.certalert.demo.DemoAccounts;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.EmailAddresses;
import com.winllc.certalert.repository.DirectoryUserRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns whatever an authentication produced - a certificate's identifiers, or the entry a
 * password bound as - into a person and their roles.
 *
 * <p>Both authentication paths converge here, so a person has the same identity and the
 * same roles whether they arrived with a certificate or a password.
 *
 * <p>Neither ever settles who somebody is by a name. A common name or a display name is
 * not unique - two people can be called the same thing, and a directory will let anybody
 * be called anything - so matching on one hands a newcomer the identity, and the roles,
 * of whoever was cached first under it. The same goes for the addresses added in this
 * application: they say what a person answers to for notifications, which is not the
 * directory saying who they are.
 *
 * <p>Roles are narrower still: {@code admin-identifiers} is matched against the DN of the
 * entry and its uid only, never an address, because a directory may let people write their
 * own {@code mail}. An entry in the list that is an address grants nothing, and is named in
 * a warning at startup.
 */
@Service
public class DirectoryPrincipalResolver {

    private static final Logger log = LoggerFactory.getLogger(DirectoryPrincipalResolver.class);

    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final DirectoryUserRepository userRepository;
    private final SecurityProperties properties;

    /**
     * Present only on a demo. There, the account the seed marks as the administrator is
     * one whatever the configuration says; everywhere else this is empty and roles come
     * from {@code admin-identifiers} alone.
     */
    private final ObjectProvider<DemoAccounts> demoAccounts;

    public DirectoryPrincipalResolver(
            DirectoryUserRepository userRepository,
            SecurityProperties properties,
            ObjectProvider<DemoAccounts> demoAccounts) {
        this.userRepository = userRepository;
        this.properties = properties;
        this.demoAccounts = demoAccounts;
    }

    /**
     * The cached entry at this distinguished name: the one somebody actually bound as.
     *
     * <p>Exactly as the directory wrote it first, which is what a sign-in that searched for
     * its entry presents - the same string the sweep cached. Failing that, ignoring case,
     * because a DN pattern builds the name from what was typed and the directory does not
     * care how it was cased.
     */
    @Transactional(readOnly = true)
    public Optional<DirectoryUser> findByDn(String dn) {
        if (dn == null || dn.isBlank()) {
            return Optional.empty();
        }
        Optional<DirectoryUser> exact = userRepository.findByDn(dn);
        if (exact.isPresent()) {
            return exact;
        }
        List<DirectoryUser> matches = userRepository.findByLowercaseDn(dn.trim().toLowerCase(Locale.ROOT));
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    /**
     * The one person the directory itself knows by any of these values - by their uid or by
     * an address it publishes for them.
     *
     * <p>For a certificate the directory does not publish, where its subject and alternative
     * names are all there is to go on. Values that name nobody are passed over; values that
     * name more than one person, or name different people, resolve to nobody. Taking the
     * first would be choosing between two people on the strength of a name.
     */
    @Transactional(readOnly = true)
    public Optional<DirectoryUser> findByDirectoryIdentifier(Collection<String> identifiers) {
        Map<Long, DirectoryUser> named = new LinkedHashMap<>();
        for (String identifier : identifiers) {
            String value = normalise(identifier);
            if (value.isEmpty()) {
                continue;
            }
            // The identifier index finds the candidates; what the directory publishes for
            // each decides, which is what keeps a common name or an added address out of it.
            for (DirectoryUser candidate : userRepository.findByIdentifier(value)) {
                if (directoryIdentifiersOf(candidate).contains(value)) {
                    named.putIfAbsent(candidate.getId(), candidate);
                }
            }
        }
        if (named.size() > 1) {
            log.warn("{} name {} different directory entries; matching none of them",
                    identifiers, named.size());
            return Optional.empty();
        }
        return named.values().stream().findFirst();
    }

    /** Builds the principal for a person the directory holds. */
    @Transactional(readOnly = true)
    public DirectoryPrincipal principalFor(DirectoryUser user, DirectoryPrincipal.AuthenticationMethod method) {
        String username = user.getEmail() != null ? user.getEmail() : user.getUid();
        return new DirectoryPrincipal(
                username == null ? user.getDn() : username,
                displayNameOf(user),
                user.getId(),
                user.getDn(),
                method,
                authoritiesFor(roleIdentifiersOf(user)));
    }

    /**
     * Builds the principal for someone who bound to the directory but is not in the cached
     * copy of it - a valid account whose entry has not been swept yet, or one the sweep's
     * filter does not reach.
     *
     * <p>Their roles are still worked out from the entry they bound as, not just the name
     * they typed. On a fresh deployment the cache is empty, and deciding admin rights from
     * the cache alone would leave nobody able to trigger the first sync.
     *
     * <p>From its DN and its uid only, as for anybody the cache does hold (see
     * {@link #roleIdentifiersOf}). Not from the name typed at the sign-in page either: a
     * search filter that also matches on {@code mail} would let somebody who wrote an
     * administrator's uid into their own {@code mail} type that uid and bind as themselves.
     *
     * @param dn the entry they bound as
     * @param uids that entry's own uid values - never its addresses, nor its common or
     *     display name, which its holder or anybody else may be able to set
     */
    public DirectoryPrincipal unknownPrincipal(
            String username,
            String dn,
            Collection<String> uids,
            DirectoryPrincipal.AuthenticationMethod method) {
        Set<String> candidates = new LinkedHashSet<>();
        if (dn != null) {
            candidates.add(normalise(dn));
        }
        uids.forEach(uid -> candidates.add(normalise(uid)));
        candidates.remove("");
        return new DirectoryPrincipal(username, username, null, dn, method, authoritiesFor(candidates));
    }

    /**
     * What {@code admin-identifiers} is matched against: the DN of the person's entry and
     * their uid, and nothing else.
     *
     * <p>Not the addresses the directory publishes for them. Plenty of directories let a
     * person write their own {@code mail} - 389 Directory Server's default access controls
     * do - so matching on one let anybody set theirs to an administrator's address and be
     * one at their next sign-in. A DN is where the entry is, and a uid is what the
     * directory's administrators assign; neither is the person's to change.
     */
    public Set<String> roleIdentifiersOf(DirectoryUser user) {
        Set<String> identifiers = new LinkedHashSet<>();
        Stream.of(user.getDn(), user.getUid())
                .map(DirectoryPrincipalResolver::normalise)
                .filter(value -> !value.isEmpty())
                .forEach(identifiers::add);
        return identifiers;
    }

    /**
     * What the directory itself says names this person: their DN, their uid and every
     * address the directory publishes for them. What a certificate the directory does not
     * publish is matched to a person by - never what decides their roles, which is
     * {@link #roleIdentifiersOf}.
     *
     * <p>Not their identifier set, which is wider for a reason of its own - a server's
     * {@code serverPOC} may name a person by their common name, and an address added here
     * binds them to the servers that use it. Neither of those says who somebody is.
     */
    @Transactional(readOnly = true)
    public Set<String> directoryIdentifiersOf(DirectoryUser user) {
        Set<String> identifiers = new LinkedHashSet<>();
        Stream.of(user.getDn(), user.getUid(), user.getEmail(), user.getMail(), user.getIcEmail(),
                        user.getInternetEmail(), user.getNiprnetEmail(), user.getSiprnetEmail())
                .map(DirectoryPrincipalResolver::normalise)
                .filter(value -> !value.isEmpty())
                .forEach(identifiers::add);
        if (user.getId() != null) {
            userRepository.findAdditionalEmailsById(user.getId()).stream()
                    .map(DirectoryPrincipalResolver::normalise)
                    .forEach(identifiers::add);
        }
        return identifiers;
    }

    /**
     * Whether {@code admin-identifiers} lists this value at all - an address it lists
     * included, although an address grants nothing - so an address added here that reads
     * like an administrator's can be refused.
     */
    public boolean isAdminIdentifier(String value) {
        String candidate = normalise(value);
        return properties.getAdminIdentifiers().stream()
                .map(DirectoryPrincipalResolver::normalise)
                .anyMatch(candidate::equals);
    }

    /**
     * The {@code admin-identifiers} entries that are addresses rather than a DN or a uid,
     * and so grant nothing (see {@link #roleIdentifiersOf}). Said at startup by
     * {@link AdminIdentifierCheck}.
     */
    public List<String> ignoredAdminIdentifiers() {
        return properties.getAdminIdentifiers().stream()
                .filter(value -> value != null && !value.isBlank())
                .filter(DirectoryPrincipalResolver::isAddressNotDn)
                .map(String::trim)
                .toList();
    }

    /** An entry is an address, and ignored, when it reads as one and is not a DN. */
    static boolean isAddressNotDn(String value) {
        String trimmed = value == null ? "" : value.trim();
        return EmailAddresses.isAddress(trimmed) && !isDn(trimmed);
    }

    private static boolean isDn(String value) {
        if (value.indexOf('=') <= 0) {
            return false;
        }
        try {
            return !new LdapName(value).isEmpty();
        } catch (InvalidNameException e) {
            return false;
        }
    }

    private Collection<GrantedAuthority> authoritiesFor(Collection<String> identifiers) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.add(new SimpleGrantedAuthority(ROLE_USER));
        Set<String> admins = adminIdentifiers();
        boolean admin = identifiers.stream().map(DirectoryPrincipalResolver::normalise).anyMatch(admins::contains);
        if (admin || isTheDemoAdministrator(identifiers)) {
            authorities.add(new SimpleGrantedAuthority(ROLE_ADMIN));
        }
        return authorities;
    }

    /** The entries that can grant the role: DNs and uids, without the addresses. */
    private Set<String> adminIdentifiers() {
        return properties.getAdminIdentifiers().stream()
                .filter(value -> value != null && !isAddressNotDn(value))
                .map(DirectoryPrincipalResolver::normalise)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Whether this is the account a demo's seed marks as the administrator.
     *
     * <p>A union with {@code admin-identifiers}, never a replacement: anybody that setting
     * names is still an administrator. What this adds is that the setting cannot take the
     * role away from the seeded account. It is read from the environment, and a deployment
     * carrying a value meant for a real directory - the compose file names its own
     * generated administrator - was quietly demoting the demo's, leaving a sign-in page
     * that called them an administrator above pages that refused them as a reader.
     *
     * <p>No effect outside a demo: the bean does not exist there.
     */
    private boolean isTheDemoAdministrator(Collection<String> identifiers) {
        DemoAccounts accounts = demoAccounts.getIfAvailable();
        return accounts != null && accounts.isSeededAdministrator(identifiers);
    }

    /**
     * Lowercased and trimmed. A distinguished name is also put in one spelling - no spaces
     * after its commas, its escapes made uniform - so a DN in {@code admin-identifiers}
     * matches however it was typed there.
     */
    static String normalise(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (trimmed.indexOf('=') > 0) {
            try {
                LdapName name = new LdapName(trimmed);
                if (!name.isEmpty()) {
                    return new LdapName(name.getRdns()).toString();
                }
            } catch (InvalidNameException e) {
                // Not a DN after all - an address with an equals sign in it, say.
            }
        }
        return trimmed;
    }

    private String displayNameOf(DirectoryUser user) {
        if (user.getDisplayName() != null) {
            return user.getDisplayName();
        }
        if (user.getCommonName() != null) {
            return user.getCommonName();
        }
        return user.getUid() != null ? user.getUid() : user.getDn();
    }
}

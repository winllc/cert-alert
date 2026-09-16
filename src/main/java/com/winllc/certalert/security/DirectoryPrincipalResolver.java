package com.winllc.certalert.security;

import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryUserRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns whatever an authentication produced - a certificate's identifiers, or the username
 * that bound successfully - into a person and their roles.
 *
 * <p>Both authentication paths converge here, so a person has the same identity and the
 * same roles whether they arrived with a certificate or a password.
 */
@Service
public class DirectoryPrincipalResolver {

    private static final Logger log = LoggerFactory.getLogger(DirectoryPrincipalResolver.class);

    public static final String ROLE_USER = "ROLE_USER";
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    private final DirectoryUserRepository userRepository;
    private final SecurityProperties properties;

    public DirectoryPrincipalResolver(DirectoryUserRepository userRepository, SecurityProperties properties) {
        this.userRepository = userRepository;
        this.properties = properties;
    }

    /** The first person the directory knows by any of these values. */
    @Transactional(readOnly = true)
    public Optional<DirectoryUser> findByAnyIdentifier(Collection<String> identifiers) {
        for (String identifier : identifiers) {
            List<DirectoryUser> matches = userRepository.findByIdentifier(normalise(identifier));
            if (matches.size() > 1) {
                // Two entries sharing a common name, most likely. Log it and take the first
                // rather than locking both people out.
                log.warn("Identifier '{}' matches {} directory entries; using '{}'",
                        identifier, matches.size(), matches.getFirst().getDn());
            }
            if (!matches.isEmpty()) {
                return Optional.of(matches.getFirst());
            }
        }
        return Optional.empty();
    }

    /** Builds the principal for a person the directory holds. */
    @Transactional(readOnly = true)
    public DirectoryPrincipal principalFor(DirectoryUser user, DirectoryPrincipal.AuthenticationMethod method) {
        Set<String> identifiers = userRepository.findIdentifiersById(user.getId());
        String username = user.getEmail() != null ? user.getEmail() : user.getUid();
        return new DirectoryPrincipal(
                username == null ? user.getDn() : username,
                displayNameOf(user),
                user.getId(),
                user.getDn(),
                method,
                authoritiesFor(identifiers));
    }

    /**
     * Builds the principal for someone who authenticated but is not in the cached directory
     * - typically a valid account whose entry has not been scraped yet.
     *
     * <p>Their roles are still worked out from every identifier the caller could gather,
     * not just the name they typed. On a fresh deployment the cache is empty, and deciding
     * admin rights from the cache alone would leave nobody able to trigger the first sync.
     *
     * @param identifiers everything known to name this person, however it was obtained
     */
    public DirectoryPrincipal unknownPrincipal(
            String username, Collection<String> identifiers, DirectoryPrincipal.AuthenticationMethod method) {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(normalise(username));
        identifiers.forEach(identifier -> candidates.add(normalise(identifier)));
        return new DirectoryPrincipal(username, username, null, null, method, authoritiesFor(candidates));
    }

    private Collection<GrantedAuthority> authoritiesFor(Collection<String> identifiers) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        authorities.add(new SimpleGrantedAuthority(ROLE_USER));
        Set<String> admins = properties.getAdminIdentifiers().stream()
                .map(DirectoryPrincipalResolver::normalise)
                .collect(java.util.stream.Collectors.toSet());
        boolean admin = identifiers.stream().map(DirectoryPrincipalResolver::normalise).anyMatch(admins::contains);
        if (admin) {
            authorities.add(new SimpleGrantedAuthority(ROLE_ADMIN));
        }
        return authorities;
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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

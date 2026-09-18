package com.winllc.certalert.security;

import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryUserRepository;
import java.util.List;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Which directory entry is signed in.
 *
 * <p>Resolved when they signed in, where it could be. On a deployment whose first sweep has
 * not finished, or one that swept after somebody signed in, there was no entry to resolve
 * them to and the session carries none; rather than treating that person as nobody until
 * they sign out and back in, their name is looked up now - one indexed query.
 *
 * <p>Two people answering to one name resolve to neither. It is no basis for showing either
 * of them the other's notifications, and less for letting either edit what the other is
 * responsible for.
 */
@Component
public class SignedInDirectoryUser {

    private final DirectoryUserRepository users;

    public SignedInDirectoryUser(DirectoryUserRepository users) {
        this.users = users;
    }

    /** @return the directory entry's id, or null where the directory does not know them */
    public Long idOf(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof DirectoryPrincipal principal)) {
            return null;
        }
        if (principal.getDirectoryUserId() != null) {
            return principal.getDirectoryUserId();
        }
        List<DirectoryUser> named = users.findByIdentifier(principal.getUsername().toLowerCase(Locale.ROOT));
        return named.size() == 1 ? named.getFirst().getId() : null;
    }

    public static boolean isAdmin(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> DirectoryPrincipalResolver.ROLE_ADMIN.equals(authority.getAuthority()));
    }
}

package com.winllc.certalert.security;

import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectorySpecifications;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.ProjectRepository;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who may manage a server's points of contact.
 *
 * <p>The list of who hears that a certificate is expiring is not annotation, so it was
 * administrators only by default. But the people who know who should be on that list are
 * the people already on it, and the ones who own the project the server belongs to -
 * waiting on an administrator to add a colleague is how a list goes stale, and a stale list
 * is a certificate nobody is told about.
 *
 * <p>So association is what grants it, per server rather than globally:
 *
 * <ul>
 *   <li>a point of contact for a server manages that server's contacts, from either place a
 *       contact comes from - named in the directory's {@code serverPOC}, or added here;
 *   <li>a member of a project manages the contacts of every server in it.
 * </ul>
 *
 * <p>Administrators keep the lot, and {@code cert-alert.security.contact-editors:
 * AUTHENTICATED} still opens it to anyone signed in. What nobody gets is a server they have
 * nothing to do with.
 */
@Component
public class ServerAccessPolicy {

    private final DirectoryServerRepository servers;
    private final DirectoryUserRepository users;
    private final ProjectRepository projects;
    private final SignedInDirectoryUser signedIn;
    private final SecurityProperties properties;

    public ServerAccessPolicy(
            DirectoryServerRepository servers,
            DirectoryUserRepository users,
            ProjectRepository projects,
            SignedInDirectoryUser signedIn,
            SecurityProperties properties) {
        this.servers = servers;
        this.users = users;
        this.projects = projects;
        this.signedIn = signedIn;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public boolean mayManageContacts(Long serverId, org.springframework.security.core.Authentication authentication) {
        if (serverId == null || authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (SignedInDirectoryUser.isAdmin(authentication)
                || properties.getContactEditors() == SecurityProperties.ContactEditors.AUTHENTICATED) {
            return true;
        }
        Long userId = signedIn.idOf(authentication);
        if (userId == null) {
            // Somebody the directory does not know: an administrator named in the
            // configuration holding no entry, which the branch above has already answered.
            return false;
        }
        return isPointOfContact(userId, serverId) || sharesProject(userId, serverId);
    }

    /**
     * Named on the server, by any of the values that could name them - each address they
     * answer to and each form of their name - or linked to a contact added here.
     */
    private boolean isPointOfContact(Long userId, Long serverId) {
        Set<String> identifiers = users.findIdentifiersById(userId);
        return servers.exists(DirectorySpecifications.pointOfContactOf(userId, identifiers)
                .and((root, query, builder) -> builder.equal(root.get("id"), serverId)));
    }

    private boolean sharesProject(Long userId, Long serverId) {
        return projects.existsSharedMembership(userId, serverId);
    }
}

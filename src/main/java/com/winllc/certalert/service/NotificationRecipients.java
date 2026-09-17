package com.winllc.certalert.service;

import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.ServerContact;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.ServerContactRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Works out who to tell about a certificate.
 *
 * <p>A person's own certificate is their business. A server's is its points of contact's,
 * from both places those come from: what the directory publishes in {@code serverPOC} and
 * what was added here. A published value is matched against the people the directory knows
 * by it - which now includes the addresses added to a person, so a list resolves to
 * everybody on it.
 *
 * <p>What cannot be resolved to a person is still a recipient, as an address. A server
 * whose only contact is a distribution list nobody has claimed can be written to; it just
 * has nobody to show a notification to when they sign in.
 */
@Component
public class NotificationRecipients {

    private final DirectoryUserRepository userRepository;
    private final DirectoryServerRepository serverRepository;
    private final ServerContactRepository contactRepository;

    public NotificationRecipients(
            DirectoryUserRepository userRepository,
            DirectoryServerRepository serverRepository,
            ServerContactRepository contactRepository) {
        this.userRepository = userRepository;
        this.serverRepository = serverRepository;
        this.contactRepository = contactRepository;
    }

    /**
     * Somebody to tell.
     *
     * @param userId the person in the directory, or null for an address with nobody behind it
     * @param address where to write, or null where the directory publishes none for them
     * @param name what to call them
     */
    public record Recipient(Long userId, String address, String name) {}

    @Transactional(readOnly = true)
    public List<Recipient> forUser(Long userId) {
        return userRepository
                .findById(userId)
                .map(user -> List.of(new Recipient(user.getId(), user.getEmail(), displayNameOf(user))))
                .orElseGet(List::of);
    }

    @Transactional(readOnly = true)
    public List<Recipient> forServer(Long serverId) {
        Map<String, Recipient> byKey = new LinkedHashMap<>();

        for (ServerContact contact : contactRepository.findByServerIdOrderByAddedAtAscIdAsc(serverId)) {
            DirectoryUser user = contact.getUser();
            add(byKey, user == null
                    ? new Recipient(null, contact.address(), contact.label())
                    : new Recipient(user.getId(), contact.address(), displayNameOf(user)));
        }

        serverRepository.findWithPocsById(serverId).ifPresent(server -> {
            for (String poc : server.getServerPocs()) {
                List<DirectoryUser> named = userRepository.findByIdentifier(poc);
                if (named.isEmpty()) {
                    // Nobody the directory knows by this. Written to anyway if it is an
                    // address: a role mailbox that nobody has claimed is still a mailbox.
                    add(byKey, new Recipient(null, poc.contains("@") ? poc : null, poc));
                    continue;
                }
                // Several is the list case: the value is an address a team answers, and
                // every one of them is a contact for this server.
                named.forEach(user -> add(byKey, new Recipient(user.getId(), user.getEmail(), displayNameOf(user))));
            }
        });

        return List.copyOf(byKey.values());
    }

    /** Keyed so the same person named twice - once by the directory, once here - is told once. */
    private void add(Map<String, Recipient> byKey, Recipient recipient) {
        if (recipient.userId() == null && recipient.address() == null) {
            return;
        }
        String key = recipient.userId() != null ? "user:" + recipient.userId() : "address:" + recipient.address();
        byKey.putIfAbsent(key, recipient);
    }

    private String displayNameOf(DirectoryUser user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        if (user.getCommonName() != null && !user.getCommonName().isBlank()) {
            return user.getCommonName();
        }
        return user.getUid() != null ? user.getUid() : user.getDn();
    }
}

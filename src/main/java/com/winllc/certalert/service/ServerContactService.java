package com.winllc.certalert.service;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.ServerContact;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.ServerContactRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds and removes the points of contact held here rather than scraped.
 *
 * <p>A contact is a person the directory knows or an address on its own. The two are not
 * really separate: an address that belongs to somebody the directory knows is linked to
 * them on the way in, so "alice@example.gov" and picking Alice out of the list end up as
 * the same row, and the filter that asks which servers a person is responsible for finds
 * it either way.
 */
@Service
public class ServerContactService {

    private static final Logger log = LoggerFactory.getLogger(ServerContactService.class);

    /**
     * Deliberately loose. This is not the place to decide what a valid address is - the
     * directory holds addresses in forms no pattern here should be rejecting - so it only
     * rules out what is obviously not one.
     */
    private static final Pattern ADDRESS = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    private final DirectoryServerRepository serverRepository;
    private final DirectoryUserRepository userRepository;
    private final ServerContactRepository contactRepository;
    private final AuditService auditService;
    private final Clock clock;

    public ServerContactService(
            DirectoryServerRepository serverRepository,
            DirectoryUserRepository userRepository,
            ServerContactRepository contactRepository,
            AuditService auditService,
            Clock clock) {
        this.serverRepository = serverRepository;
        this.userRepository = userRepository;
        this.contactRepository = contactRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ServerContact> list(Long serverId) {
        requireServer(serverId);
        return contactRepository.findByServerIdOrderByAddedAtAscIdAsc(serverId);
    }

    /** Adds the person with this id as a contact. */
    @Transactional
    public ServerContact addUser(Long serverId, Long userId, String addedBy) {
        DirectoryServer server = requireServer(serverId);
        DirectoryUser user = userRepository.findById(userId).orElseThrow(() -> ResourceNotFoundException.user(userId));

        if (contactRepository.existsByServerIdAndUserId(serverId, userId)) {
            throw new AlreadyExistsException(
                    "Already a point of contact",
                    "%s is already a point of contact for this server".formatted(displayNameOf(user)));
        }
        String address = normalise(user.getEmail());
        if (address != null && contactRepository.existsByServerIdAndEmail(serverId, address)) {
            throw new AlreadyExistsException(
                    "Already a point of contact",
                    "%s is already a point of contact for this server".formatted(address));
        }

        ServerContact contact = ServerContact.forUser(server, user, addedBy, Instant.now(clock));
        contactRepository.save(contact);
        recordAdded(server, contact, addedBy);
        log.info("Added {} as a point of contact for {}, by {}", user.getDn(), server.getDn(), addedBy);
        return contact;
    }

    /**
     * Adds an address as a contact, linking it to the person it belongs to where the
     * directory knows exactly one such person.
     */
    @Transactional
    public ServerContact addEmail(Long serverId, String email, String addedBy) {
        DirectoryServer server = requireServer(serverId);
        String address = normalise(email);
        if (address == null || !ADDRESS.matcher(address).matches()) {
            throw new IllegalArgumentException("'%s' is not an email address".formatted(email));
        }
        if (contactRepository.existsByServerIdAndEmail(serverId, address)) {
            throw new AlreadyExistsException(
                    "Already a point of contact",
                    "%s is already a point of contact for this server".formatted(address));
        }

        DirectoryUser owner = resolveOwner(address);
        if (owner != null) {
            if (contactRepository.existsByServerIdAndUserId(serverId, owner.getId())) {
                throw new AlreadyExistsException(
                        "Already a point of contact",
                        "%s is already a point of contact for this server".formatted(displayNameOf(owner)));
            }
            ServerContact contact = ServerContact.forUser(server, owner, addedBy, Instant.now(clock));
            contactRepository.save(contact);
            recordAdded(server, contact, addedBy);
            log.info("Added {} as a point of contact for {} (matched {}), by {}",
                    owner.getDn(), server.getDn(), address, addedBy);
            return contact;
        }

        ServerContact contact = ServerContact.forEmail(server, address, addedBy, Instant.now(clock));
        contactRepository.save(contact);
        recordAdded(server, contact, addedBy);
        log.info("Added {} as a point of contact for {}, by {}", address, server.getDn(), addedBy);
        return contact;
    }

    @Transactional
    public void remove(Long serverId, Long contactId) {
        ServerContact contact = contactRepository
                .findByIdAndServerId(contactId, serverId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No contact with id %d on server %d".formatted(contactId, serverId)));
        DirectoryServer server = contact.getServer();
        contactRepository.delete(contact);
        auditService.record(AuditEvent.about(
                        AuditEvent.SubjectRef.of(server),
                        AuditAction.CONTACT_REMOVED,
                        "Removed %s as a point of contact".formatted(contact.label()),
                        Instant.now(clock))
                .by(AuditActors.current(AuditActors.SYSTEM))
                .to(contact.address() != null ? contact.address() : contact.label()));
        log.info("Removed point of contact {} from server {}", contact.label(), server.getDn());
    }

    /**
     * The addresses to write to about this server's certificates. Called only when an alert
     * is actually being raised, which is why the contacts are not carried on the server row.
     */
    @Transactional(readOnly = true)
    public List<String> addressesFor(Long serverId) {
        return contactRepository.findByServerIdOrderByAddedAtAscIdAsc(serverId).stream()
                .map(ServerContact::address)
                .filter(address -> address != null && !address.isBlank())
                .distinct()
                .toList();
    }

    private void recordAdded(DirectoryServer server, ServerContact contact, String addedBy) {
        String how = contact.getUser() != null ? "directory entry" : "address";
        auditService.record(AuditEvent.about(
                        AuditEvent.SubjectRef.of(server),
                        AuditAction.CONTACT_ADDED,
                        "Added %s as a point of contact (%s)".formatted(contact.label(), how),
                        Instant.now(clock))
                .by(addedBy != null ? addedBy : AuditActors.current(AuditActors.SYSTEM))
                .to(contact.address() != null ? contact.address() : contact.label()));
    }

    /**
     * The person an address belongs to, where there is exactly one. Two people answering to
     * the same value is not an error - identifiers include names, and names repeat - but it
     * is no basis for linking a contact to one of them.
     */
    private DirectoryUser resolveOwner(String address) {
        List<DirectoryUser> matches = userRepository.findByIdentifier(address);
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.size() > 1) {
            log.debug("'{}' names {} directory entries; adding it as a plain address", address, matches.size());
        }
        return null;
    }

    private DirectoryServer requireServer(Long serverId) {
        return serverRepository.findById(serverId).orElseThrow(() -> ResourceNotFoundException.server(serverId));
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

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }
}

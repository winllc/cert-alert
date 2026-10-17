package com.winllc.certalert.service;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.EmailAddresses;
import com.winllc.certalert.domain.UserEmailAlias;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.UserEmailAliasRepository;
import com.winllc.certalert.security.DirectoryPrincipalResolver;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds and removes the addresses a person answers to beyond the ones the directory
 * publishes.
 *
 * <p>Every change re-derives that person's identifier set, which is what a server's
 * {@code serverPOC} is matched against. Adding an address makes the servers named after it
 * theirs straight away rather than at the next sweep, and removing one takes them back -
 * but only if the directory does not publish that address too, which is why the set is
 * rebuilt from both sources rather than having the one value struck out of it.
 *
 * <p>What it does not do is change who somebody is. Administrator rights are decided from
 * what the directory publishes alone (see {@link DirectoryPrincipalResolver}), so an
 * address added here never grants them - and one that {@code admin-identifiers} names is
 * refused outright, rather than accepted and left to look as if it might.
 */
@Service
public class UserEmailAliasService {

    private static final Logger log = LoggerFactory.getLogger(UserEmailAliasService.class);

    /** As loose as the one on contacts, and for the same reason: real directories are messy. */
    private final DirectoryUserRepository userRepository;
    private final UserEmailAliasRepository aliasRepository;
    private final AuditService auditService;
    private final DirectoryPrincipalResolver principals;
    private final Clock clock;

    public UserEmailAliasService(
            DirectoryUserRepository userRepository,
            UserEmailAliasRepository aliasRepository,
            AuditService auditService,
            DirectoryPrincipalResolver principals,
            Clock clock) {
        this.userRepository = userRepository;
        this.aliasRepository = aliasRepository;
        this.auditService = auditService;
        this.principals = principals;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<UserEmailAlias> list(Long userId) {
        requireUser(userId);
        return aliasRepository.findByUserIdOrderByAddressAsc(userId);
    }

    @Transactional
    public UserEmailAlias add(Long userId, String address, UserEmailAlias.Kind kind, String label, String addedBy) {
        DirectoryUser user = requireUser(userId);
        String normalised = normalise(address);
        if (normalised == null || !EmailAddresses.isAddress(normalised)) {
            throw new IllegalArgumentException("'%s' is not an email address".formatted(address));
        }
        if (principals.isAdminIdentifier(normalised)) {
            // Where anybody signed in may add addresses, this is somebody naming themselves
            // after an administrator. It would grant nothing, but it is refused rather than
            // kept: an address list that reads as if it might is not one to leave lying about.
            log.warn("Refused to add {} to {} for {}: it is named in cert-alert.security.admin-identifiers",
                    normalised, user.getDn(), addedBy);
            throw new IllegalArgumentException(("%s is an administrator's identifier "
                    + "(cert-alert.security.admin-identifiers), so it cannot be added as an address here. "
                    + "Administrator rights come from the directory alone.").formatted(normalised));
        }
        if (aliasRepository.existsByUserIdAndAddress(userId, normalised)) {
            throw new AlreadyExistsException(
                    "Already an address",
                    "%s is already one of this person's addresses".formatted(normalised));
        }

        UserEmailAlias alias =
                new UserEmailAlias(user, normalised, kind, label, addedBy, Instant.now(clock));
        aliasRepository.save(alias);
        reindex(user);

        auditService.record(AuditEvent.about(
                        AuditEvent.SubjectRef.of(user),
                        AuditAction.ADDRESS_ADDED,
                        "Added %s as %s address this person answers to"
                                .formatted(normalised, alias.getKind() == UserEmailAlias.Kind.GROUP
                                        ? "a group"
                                        : "an"),
                        Instant.now(clock))
                .by(addedBy != null ? addedBy : AuditActors.current(AuditActors.SYSTEM))
                .to(normalised));
        log.info("Added address {} to {}, by {}", normalised, user.getDn(), addedBy);
        return alias;
    }

    @Transactional
    public void remove(Long userId, Long aliasId) {
        UserEmailAlias alias = aliasRepository
                .findByIdAndUserId(aliasId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No address with id %d on user %d".formatted(aliasId, userId)));
        DirectoryUser user = alias.getUser();
        String address = alias.getAddress();
        aliasRepository.delete(alias);
        aliasRepository.flush();
        reindex(user);

        auditService.record(AuditEvent.about(
                        AuditEvent.SubjectRef.of(user),
                        AuditAction.ADDRESS_REMOVED,
                        "Removed %s from the addresses this person answers to".formatted(address),
                        Instant.now(clock))
                .by(AuditActors.current(AuditActors.SYSTEM))
                .to(address));
        log.info("Removed address {} from {}", address, user.getDn());
    }

    /**
     * Rebuilds what a {@code serverPOC} is matched against, from the directory's attributes
     * and whatever addresses remain. Rebuilding rather than adding or striking out one value
     * is what keeps a removal from taking away an address the directory publishes anyway.
     */
    private void reindex(DirectoryUser user) {
        user.refreshIdentifiers(user.getEmail(), aliasRepository.findAddressesByUserId(user.getId()));
        userRepository.save(user);
    }

    private DirectoryUser requireUser(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> ResourceNotFoundException.user(userId));
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }
}

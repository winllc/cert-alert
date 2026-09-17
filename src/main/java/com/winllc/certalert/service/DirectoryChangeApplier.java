package com.winllc.certalert.service;

import com.winllc.certalert.ldap.ChangelogEntry;
import com.winllc.certalert.ldap.LdapEntryReader;
import com.winllc.certalert.ldap.LdapServerEntry;
import com.winllc.certalert.ldap.LdapUserEntry;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one changelog entry to the cache.
 *
 * <p>The changelog says <em>what</em> changed, and this deliberately ignores its account of
 * <em>how</em>. A {@code changeLogEntry} carries the modifications as an LDIF fragment, and
 * replaying those would mean re-implementing LDAP modify semantics - add, delete and replace
 * of individual attribute values, binary or not - against a format directories spell
 * differently. Instead the named entry is re-read and pushed through exactly the path a full
 * sweep uses.
 *
 * <p>That costs one read per changed entry, which is nothing beside a sweep of the whole
 * tree, and buys three things: applying a change is idempotent, so a change replayed after a
 * crash is harmless; the result cannot drift from what a sweep would have written; and the
 * certificate reconciliation, roll-ups and alert transitions all come along for free.
 */
@Service
@ConditionalOnProperty(prefix = "cert-alert.ldap.changelog", name = "enabled", havingValue = "true")
public class DirectoryChangeApplier {

    private static final Logger log = LoggerFactory.getLogger(DirectoryChangeApplier.class);

    private final LdapEntryReader entryReader;
    private final DirectoryPersistenceService persistenceService;
    private final DirectoryUserRepository userRepository;
    private final DirectoryServerRepository serverRepository;

    public DirectoryChangeApplier(
            LdapEntryReader entryReader,
            DirectoryPersistenceService persistenceService,
            DirectoryUserRepository userRepository,
            DirectoryServerRepository serverRepository) {
        this.entryReader = entryReader;
        this.persistenceService = persistenceService;
        this.userRepository = userRepository;
        this.serverRepository = serverRepository;
    }

    /** What one change did. */
    public enum Outcome {
        /** The cache changed. */
        APPLIED,
        /** The change was to something this application does not track. */
        IGNORED
    }

    /**
     * Applies one change, in one transaction. The transaction is here rather than on the
     * helpers below because those are called from inside this class, where a proxy would
     * not intercept them - and a modifying delete outside a transaction simply fails.
     */
    @Transactional
    public Outcome apply(ChangelogEntry change, Instant now) {
        return switch (change.changeType()) {
            case DELETE -> removed(change.targetDn());
            case ADD, MODIFY -> reread(change.targetDn(), now);
            // A rename moves the entry: the old name is gone and the new one is read fresh.
            case MODRDN -> {
                Outcome removed = removed(change.targetDn());
                Outcome added = reread(change.effectiveDn(), now);
                yield added == Outcome.APPLIED || removed == Outcome.APPLIED ? Outcome.APPLIED : Outcome.IGNORED;
            }
            case UNKNOWN -> {
                log.debug("Ignoring change {} of unrecognised type at '{}'",
                        change.changeNumber(), change.targetDn());
                yield Outcome.IGNORED;
            }
        };
    }

    /**
     * Re-reads the named entry and writes it, or removes it from the cache if it is gone or
     * no longer matches.
     *
     * <p>That last case matters: an entry whose objectClass or status changed so that the
     * sweep's filter no longer selects it has effectively left, and leaving a stale copy
     * behind would keep it in the search tables until somebody noticed.
     */
    private Outcome reread(String dn, Instant now) {
        Optional<LdapUserEntry> user = entryReader.readUser(dn);
        if (user.isPresent()) {
            persistenceService.upsertUsers(List.of(user.get()), now, AuditActors.CHANGELOG);
            log.debug("Applied change to person '{}'", dn);
            return Outcome.APPLIED;
        }

        Optional<LdapServerEntry> server = entryReader.readServer(dn);
        if (server.isPresent()) {
            persistenceService.upsertServers(List.of(server.get()), now, AuditActors.CHANGELOG);
            log.debug("Applied change to server '{}'", dn);
            return Outcome.APPLIED;
        }

        return removed(dn);
    }

    /** Drops an entry from the cache, if it was ever in it. */
    private Outcome removed(String dn) {
        int deleted = userRepository.deleteByDn(dn) + serverRepository.deleteByDn(dn);
        if (deleted > 0) {
            log.debug("Removed '{}' from the cache", dn);
            return Outcome.APPLIED;
        }
        return Outcome.IGNORED;
    }
}

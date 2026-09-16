package com.winllc.certalert.service;

import com.winllc.certalert.ldap.LdapDirectoryClient;
import com.winllc.certalert.ldap.LdapServerEntry;
import com.winllc.certalert.ldap.LdapUserEntry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Scrapes the directory and hands each entry to {@link DirectoryPersistenceService}.
 *
 * <p>The LDAP reads happen outside any transaction on purpose: holding a database
 * connection open across a directory-wide network read is a good way to exhaust the pool
 * on a large tree.
 */
@Service
public class DirectorySyncService {

    private static final Logger log = LoggerFactory.getLogger(DirectorySyncService.class);

    private final LdapDirectoryClient directoryClient;
    private final DirectoryPersistenceService persistenceService;
    private final Clock clock;

    public DirectorySyncService(
            LdapDirectoryClient directoryClient, DirectoryPersistenceService persistenceService, Clock clock) {
        this.directoryClient = directoryClient;
        this.persistenceService = persistenceService;
        this.clock = clock;
    }

    /** Runs a full sync of users and servers. */
    public DirectorySyncResult sync() {
        long startedAt = System.nanoTime();
        Instant now = Instant.now(clock);

        List<LdapUserEntry> users = directoryClient.fetchUsers();
        List<LdapServerEntry> servers = directoryClient.fetchServers();
        log.info("Directory scrape returned {} user(s) and {} server(s)", users.size(), servers.size());

        Totals totals = new Totals();
        for (LdapUserEntry user : users) {
            totals.usersCreated += apply(totals, user.dn(), () -> persistenceService.upsertUser(user, now));
        }
        for (LdapServerEntry server : servers) {
            totals.serversCreated += apply(totals, server.dn(), () -> persistenceService.upsertServer(server, now));
        }

        DirectorySyncResult result = new DirectorySyncResult(
                users.size(),
                totals.usersCreated,
                servers.size(),
                totals.serversCreated,
                totals.certificatesCached,
                totals.certificatesRemoved,
                totals.alertsRaised,
                totals.errors,
                Duration.ofNanos(System.nanoTime() - startedAt));

        log.info(
                "Directory sync finished in {} ms: {} user(s) ({} new), {} server(s) ({} new), "
                        + "{} certificate(s) cached, {} removed, {} alert(s), {} error(s)",
                result.duration().toMillis(),
                result.usersSeen(),
                result.usersCreated(),
                result.serversSeen(),
                result.serversCreated(),
                result.certificatesCached(),
                result.certificatesRemoved(),
                result.alertsRaised(),
                result.errors());
        return result;
    }

    /** Runs one upsert, folding its counts into the totals and isolating its failures. */
    private int apply(Totals totals, String dn, java.util.function.Supplier<UpsertOutcome> upsert) {
        try {
            UpsertOutcome outcome = upsert.get();
            totals.certificatesCached += outcome.certificatesCached();
            totals.certificatesRemoved += outcome.certificatesRemoved();
            totals.alertsRaised += outcome.alertsRaised();
            return outcome.created() ? 1 : 0;
        } catch (RuntimeException e) {
            totals.errors++;
            log.error("Failed to sync directory entry '{}'", dn, e);
            return 0;
        }
    }

    /** Mutable accumulator; the sync touches it from a single thread. */
    private static final class Totals {
        private int usersCreated;
        private int serversCreated;
        private int certificatesCached;
        private int certificatesRemoved;
        private int alertsRaised;
        private int errors;
    }
}

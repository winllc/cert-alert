package com.winllc.certalert.service;

import com.winllc.certalert.config.RevocationProperties;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.revocation.CrlStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Asks the issuing authorities which cached certificates have been revoked.
 *
 * <p>The one question about a certificate that cannot be answered from the certificate. Its
 * expiry is a date it carries, and every page here reads it. Its revocation is a decision
 * made somewhere else, published somewhere else, and nothing about the certificate changes
 * when it happens - a key reported stolen in March is still valid until 2028 as far as the
 * directory and every roll-up here is concerned.
 *
 * <p>Its own schedule rather than part of a sweep, because it is a different kind of work
 * against a different system: a sweep reads the directory, and this reads whatever the
 * certificates name. It runs after both sweeps, so it asks about a cache that is current.
 *
 * <p>Only the certificates an entry is currently working with are asked about - the pair,
 * for a person. An authority revoking a certificate that has already been replaced is not
 * news, and asking about every superseded certificate a directory has ever held would
 * multiply the work by however long it has been running.
 */
@Service
public class RevocationService {

    private static final Logger log = LoggerFactory.getLogger(RevocationService.class);

    /** Entries per transaction, not certificates: a person is two. */
    private static final int PAGE_SIZE = 200;

    private final RevocationPageProcessor pageProcessor;
    private final CrlStore crls;
    private final RevocationProperties properties;
    private final Clock clock;

    public RevocationService(
            RevocationPageProcessor pageProcessor,
            CrlStore crls,
            RevocationProperties properties,
            Clock clock) {
        this.pageProcessor = pageProcessor;
        this.crls = crls;
        this.properties = properties;
        this.clock = clock;
    }

    /** What one run found. */
    public record Result(int entries, int checked, int revoked, int unknown, Duration duration) {

        static final Result NOTHING = new Result(0, 0, 0, 0, Duration.ZERO);
    }

    /**
     * Checks every entry's current certificates.
     *
     * <p>The lists are fetched once each and held for as long as the authority says they
     * are good for, so a hundred thousand entries issued by half a dozen authorities cost
     * half a dozen downloads rather than a hundred thousand.
     */
    public Result checkAll() {
        if (!properties.isEnabled()) {
            return Result.NOTHING;
        }
        Instant now = Instant.now(clock);
        long startedNanos = System.nanoTime();
        // Held lists are fine within a run and stale between them: a run happening at all
        // is the moment to find out what has been published since the last one.
        crls.clear();

        int entries = 0;
        int checked = 0;
        int revoked = 0;
        int unknown = 0;
        for (OwnerType type : OwnerType.values()) {
            long cursor = 0;
            while (true) {
                RevocationPageProcessor.Page page = pageProcessor.check(type, cursor, PAGE_SIZE, now);
                if (page.entries() == 0) {
                    break;
                }
                entries += page.entries();
                checked += page.checked();
                revoked += page.revoked();
                unknown += page.unknown();
                cursor = page.lastOwnerId();
            }
        }

        Result result = new Result(
                entries, checked, revoked, unknown, Duration.ofNanos(System.nanoTime() - startedNanos));
        if (revoked > 0) {
            log.warn("Revocation check finished in {} ms: {} entries, {} certificate(s) checked, "
                            + "{} REVOKED, {} unanswered",
                    result.duration().toMillis(), entries, checked, revoked, unknown);
        } else {
            log.info("Revocation check finished in {} ms: {} entries, {} certificate(s) checked, "
                            + "none revoked, {} unanswered",
                    result.duration().toMillis(), entries, checked, unknown);
        }
        return result;
    }
}

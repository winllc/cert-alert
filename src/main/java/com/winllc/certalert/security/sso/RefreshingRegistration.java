package com.winllc.certalert.security.sso;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;

/**
 * The relying party built from the SAML identity provider's metadata, read the first time it
 * is wanted and again once it is older than {@code cert-alert.sso.saml.metadata-refresh}, so
 * a provider that rolls its signing certificate is followed without a restart.
 *
 * <p>A read that fails keeps the last copy that worked, and is tried again a minute later. A
 * request that comes while another is reading uses the copy in hand rather than waiting. A
 * response whose signature does not verify can bring the next read forward, at most once
 * every five minutes, since a rolled key is the usual reason for one.
 */
final class RefreshingRegistration implements Supplier<RelyingPartyRegistration> {

    private static final Logger log = LoggerFactory.getLogger(RefreshingRegistration.class);

    /** After a failed read, how long the last good copy serves before the next try. */
    static final Duration RETRY = Duration.ofMinutes(1);

    /** How often a signature failure may bring a read forward. */
    static final Duration EARLY_READS = Duration.ofMinutes(5);

    private record Held(RelyingPartyRegistration registration, Instant readAt, Instant nextReadAt) {}

    private final Supplier<RelyingPartyRegistration> read;
    private final Duration lifetime;
    private final String location;
    private final Clock clock;
    private final ReentrantLock reading = new ReentrantLock();
    private final AtomicReference<Instant> lastEarlyRead = new AtomicReference<>();
    private volatile Held held;

    /**
     * @param read builds the registration from the metadata, reading it afresh each time
     * @param lifetime how long a copy is used before it is read again; zero or less: never again
     * @param location where the metadata is, for the log
     */
    RefreshingRegistration(Supplier<RelyingPartyRegistration> read, Duration lifetime, String location, Clock clock) {
        this.read = read;
        this.lifetime = lifetime;
        this.location = location;
        this.clock = clock;
    }

    @Override
    public RelyingPartyRegistration get() {
        Held current = held;
        if (current != null && clock.instant().isBefore(current.nextReadAt())) {
            return current.registration();
        }
        if (current == null) {
            reading.lock();
        } else if (!reading.tryLock()) {
            // Somebody else is reading it; the copy in hand serves meanwhile.
            return current.registration();
        }
        try {
            current = held;
            Instant now = clock.instant();
            if (current != null && now.isBefore(current.nextReadAt())) {
                return current.registration();
            }
            try {
                RelyingPartyRegistration fresh = read.get();
                held = new Held(fresh, now, nextRead(now));
                return fresh;
            } catch (RuntimeException e) {
                if (current == null) {
                    throw e;
                }
                log.warn("Could not read the SAML identity provider's metadata from {} again; keeping the copy read at {}"
                        + " and trying again in {}: {}", location, current.readAt(), RETRY, e.toString());
                held = new Held(current.registration(), current.readAt(), now.plus(RETRY));
                return current.registration();
            }
        } finally {
            reading.unlock();
        }
    }

    /**
     * Reads the metadata again now, after a response whose signature did not verify - unless
     * that was done less than {@link #EARLY_READS} ago.
     *
     * @return the registration read, or null where nothing new was read
     */
    RelyingPartyRegistration readAgainAfterSignatureFailure() {
        Instant now = clock.instant();
        Instant last = lastEarlyRead.get();
        if (last != null && now.isBefore(last.plus(EARLY_READS))) {
            return null;
        }
        if (!lastEarlyRead.compareAndSet(last, now)) {
            return null;
        }
        Held current = held;
        if (current != null) {
            held = new Held(current.registration(), current.readAt(), now);
        }
        RelyingPartyRegistration fresh = get();
        return current == null || fresh != current.registration() ? fresh : null;
    }

    private Instant nextRead(Instant now) {
        if (lifetime == null || lifetime.isZero() || lifetime.isNegative()) {
            return Instant.MAX;
        }
        return now.plus(lifetime);
    }
}

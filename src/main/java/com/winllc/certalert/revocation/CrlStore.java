package com.winllc.certalert.revocation;

import com.winllc.certalert.config.RevocationProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fetches certificate revocation lists, and holds on to them.
 *
 * <p>Holding on to them is the point. A CRL is one authority's list of every serial it has
 * revoked, so one download answers for every certificate that authority issued - and a
 * directory of a hundred thousand entries is issued by a handful of authorities. Fetching
 * per certificate would be a hundred thousand downloads of the same few files.
 *
 * <p>A CRL says when it will next be published, and that is what decides how long it is
 * kept: reusing one past its {@code nextUpdate} would mean missing the revocation it was
 * published to announce. Where it says nothing, or says something implausible, the
 * configured ceiling applies.
 *
 * <p>A distribution point that did not answer, or answered with a list already past its
 * {@code nextUpdate}, is not asked again for {@link #RETRY_AFTER}: a directory names the
 * same few points on every certificate, and asking once per certificate is a timeout per
 * certificate when one of them is down.
 */
@Component
public class CrlStore {

    private static final Logger log = LoggerFactory.getLogger(CrlStore.class);

    private static final String ACCEPT = "application/pkix-crl, application/x-pkcs7-crl, */*";

    /** How long a distribution point that gave no usable list is left alone. */
    static final Duration RETRY_AFTER = Duration.ofMinutes(2);

    /**
     * Where a list is downloaded from: a {@link RevocationFetcher}, or a test's stand-in.
     */
    interface Source {

        byte[] get(String url, String accept) throws IOException;

        /** How long a caller downloading with this waits on somebody else's download instead. */
        Duration patience();

        static Source of(RevocationFetcher fetcher) {
            return new Source() {
                @Override
                public byte[] get(String url, String accept) throws IOException {
                    return fetcher.get(url, accept);
                }

                @Override
                public Duration patience() {
                    return fetcher.maxTime();
                }
            };
        }
    }

    private final RevocationProperties properties;
    /** What the scheduled check fetches with; sign-in brings its own, with shorter timeouts. */
    private final RevocationFetcher fetcher;
    private final Source source;
    private final Clock clock;
    /**
     * By distribution point. The value is the answer rather than the list, so that a second
     * caller arriving while the first is still downloading waits for it instead of starting
     * a download of its own.
     */
    private final Map<String, CompletableFuture<Held>> held = new ConcurrentHashMap<>();

    @Autowired
    public CrlStore(RevocationProperties properties, FetchPolicy policy, Clock clock) {
        // A distribution point that redirects is ordinary, and followed - to http or https
        // only, and each hop checked against the policy. One that redirects to somewhere
        // else's idea of a CRL is caught by the signature check.
        this(properties, new RevocationFetcher(
                policy,
                properties.getConnectTimeout(),
                properties.getReadTimeout(),
                properties.getMaxFetchTime(),
                properties.getMaxCrlBytes()), null, clock);
    }

    /** With the scheduled check's downloads going to a stand-in, for a test. */
    CrlStore(RevocationProperties properties, Source source, Clock clock) {
        this(properties, null, source, clock);
    }

    private CrlStore(RevocationProperties properties, RevocationFetcher fetcher, Source source, Clock clock) {
        this.properties = properties;
        this.fetcher = fetcher;
        this.source = source != null ? source : Source.of(fetcher);
        this.clock = clock;
    }

    /** The fetcher the scheduled check uses, for the responder questions it asks as well. */
    public RevocationFetcher fetcher() {
        return fetcher;
    }

    /** A CRL and what is known about it. */
    public record Fetched(X509CRL crl, String url, boolean verified) {}

    /**
     * The first of these distribution points that answers.
     *
     * <p>A list is returned whatever its dates say; whether one past its {@code nextUpdate}
     * still answers anything is the caller's decision.
     *
     * @param urls the certificate's distribution points, in its own order
     * @param issuer the certificate of whoever issued it, for checking the signature, or
     *     null where it is not known
     */
    public Optional<Fetched> fetch(List<String> urls, X509Certificate issuer) {
        return fetch(urls, issuer, source);
    }

    /**
     * The same, downloading anything not already held with this fetcher - which is how a
     * sign-in, with somebody waiting, asks with shorter timeouts than the nightly job while
     * still sharing what either of them has already downloaded. It waits on a download
     * already under way no longer than its own would take.
     */
    public Optional<Fetched> fetch(List<String> urls, X509Certificate issuer, RevocationFetcher with) {
        return fetch(urls, issuer, Source.of(with));
    }

    Optional<Fetched> fetch(List<String> urls, X509Certificate issuer, Source with) {
        for (String url : urls) {
            Optional<Fetched> crl = fetchOne(url, issuer, with);
            if (crl.isPresent()) {
                return crl;
            }
        }
        return Optional.empty();
    }

    /** Forgets everything held, so the next check fetches again. */
    public void clear() {
        held.clear();
    }

    /**
     * One download per distribution point, however many ask for it at once.
     *
     * <p>Whoever gets there first puts an unfinished answer in the map and goes to fetch;
     * everybody else finds it and waits on it. Looking, missing and then fetching - which
     * is the obvious way to write this - means every worker that looks before the first
     * one finishes goes and fetches the same list, and a handful of downloads for the whole
     * directory becomes a handful per batch. That is the property this cache exists for.
     *
     * <p>A finished answer is reused until its time is up - a list until its next update or
     * the ceiling, a failure for {@link #RETRY_AFTER} - and is then replaced by whoever
     * next asks.
     */
    private Optional<Fetched> fetchOne(String url, X509Certificate issuer, Source with) {
        while (true) {
            CompletableFuture<Held> current = held.get(url);
            if (current == null) {
                CompletableFuture<Held> mine = new CompletableFuture<>();
                if (held.putIfAbsent(url, mine) == null) {
                    return download(url, issuer, with, mine);
                }
                continue;
            }
            if (!current.isDone()) {
                // Somebody else is fetching it; theirs is the answer, if it comes in time.
                return answer(await(current, url, with.patience()), url, issuer);
            }
            Held finished = current.join();
            if (finished.reusableAt(clock.instant())) {
                return answer(finished, url, issuer);
            }
            CompletableFuture<Held> mine = new CompletableFuture<>();
            if (held.replace(url, current, mine)) {
                return download(url, issuer, with, mine);
            }
            // Somebody else replaced it first: look again.
        }
    }

    private Optional<Fetched> download(String url, X509Certificate issuer, Source with, CompletableFuture<Held> mine) {
        try {
            byte[] bytes = with.get(url, ACCEPT);
            X509CRL crl = (X509CRL) CertificateFactory.getInstance("X.509")
                    .generateCRL(new ByteArrayInputStream(bytes));
            mine.complete(new Held(crl, keepUntil(crl, clock.instant())));
            log.info("Fetched a CRL from {}: {} entries, next update {}",
                    url,
                    crl.getRevokedCertificates() == null ? 0 : crl.getRevokedCertificates().size(),
                    crl.getNextUpdate());
            return Optional.of(verify(crl, url, issuer));
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            // One unreachable distribution point is not the end of the check; the next one
            // in the certificate may answer, and an unanswered question reads as unknown.
            if (e instanceof FetchPolicy.RefusedDestinationException) {
                // Not a network fault: a certificate naming somewhere this will not go.
                log.warn("Did not fetch a CRL from {}: {}", url, describe(e));
            } else {
                log.info("Could not fetch a CRL from {}: {}", url, describe(e));
            }
            return Optional.empty();
        } finally {
            // Whatever went wrong, nobody waiting on this is left waiting.
            mine.complete(new Held(null, clock.instant().plus(RETRY_AFTER)));
        }
    }

    private Optional<Fetched> answer(Held answer, String url, X509Certificate issuer) {
        return answer == null || answer.crl() == null
                ? Optional.empty()
                : Optional.of(verify(answer.crl(), url, issuer));
    }

    /** Somebody else's download, waited on no longer than this caller would wait on its own. */
    private static Held await(CompletableFuture<Held> holding, String url, Duration patience) {
        try {
            return holding.get(Math.max(1, patience.toNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            log.info("Gave up waiting {} for a CRL from {} that is still being fetched", patience, url);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    /**
     * Whether this list is provably the authority's own.
     *
     * <p>Not fatal either way - what to do about an unverified CRL is the caller's decision
     * and a configured one - but it is recorded, because a list fetched over plain HTTP
     * from a URL the certificate names is only as trustworthy as whatever answered.
     */
    private Fetched verify(X509CRL crl, String url, X509Certificate issuer) {
        if (issuer == null) {
            return new Fetched(crl, url, false);
        }
        try {
            crl.verify(issuer.getPublicKey());
            return new Fetched(crl, url, true);
        } catch (GeneralSecurityException e) {
            log.warn("The CRL at {} is not signed by {}: {}",
                    url, issuer.getSubjectX500Principal().getName(), describe(e));
            return new Fetched(crl, url, false);
        }
    }

    /**
     * How long this list may be reused. Its own {@code nextUpdate} decides, because that is
     * when the authority said the next one would exist; the configured ceiling stops a list
     * with a distant or missing next update from being held indefinitely. One already past
     * its next update is what the point is serving now, and is asked for again after
     * {@link #RETRY_AFTER} rather than once per certificate.
     */
    private Instant keepUntil(X509CRL crl, Instant now) {
        Instant ceiling = now.plus(properties.getCrlCacheTtl());
        if (crl.getNextUpdate() == null) {
            return ceiling;
        }
        Instant next = crl.getNextUpdate().toInstant();
        if (!next.isAfter(now)) {
            return now.plus(RETRY_AFTER);
        }
        return next.isBefore(ceiling) ? next : ceiling;
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /** A list, or - with none - a distribution point that gave none, and until when. */
    private record Held(X509CRL crl, Instant until) {

        boolean reusableAt(Instant now) {
            return now.isBefore(until);
        }
    }
}

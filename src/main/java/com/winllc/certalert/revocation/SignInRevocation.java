package com.winllc.certalert.revocation;

import com.winllc.certalert.config.RevocationProperties;
import com.winllc.certalert.domain.RevocationStatus;
import com.winllc.certalert.security.EnsX509Properties;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.cert.X509CRL;
import java.security.cert.X509CRLEntry;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Whether a client certificate presented to sign in has been revoked
 * ({@code ENS_X509_REVOCATION}).
 *
 * <p>Separate from the nightly check of the directory's certificates, because the question
 * is asked with somebody waiting: the network is given five seconds to connect and ten to
 * answer, through a fetcher of its own held to the same {@link FetchPolicy} - http and https
 * only, no link-local or metadata address - and nothing process-wide is changed to do it.
 * Lists downloaded by either are shared through the {@link CrlStore}.
 *
 * <ul>
 *   <li>{@code ocsp} asks the responder the certificate names, or
 *       {@code ENS_X509_OCSP_RESPONDER_URL} in its place. The answer is checked by the
 *       platform's path validator (see {@link OcspCheck}).
 *   <li>{@code crl} reads the issuer's list - from {@code ENS_X509_CRL_DIR} where that is
 *       set, in which case nothing is fetched, or else from the certificate's distribution
 *       points. Only a list provably signed by the issuer, and not past its next update,
 *       answers.
 *   <li>{@code ocsp-then-crl} asks the responder, and reads the list when that gives no
 *       answer.
 * </ul>
 *
 * <p>A good or revoked answer is reused for {@code ENS_X509_REVOCATION_CACHE} per issuer and
 * serial number, so a browser's every request does not ask again; no answer is reused for a
 * few seconds only, so a responder that comes back is noticed.
 */
@Component
public class SignInRevocation {

    private static final Logger log = LoggerFactory.getLogger(SignInRevocation.class);

    /** Somebody is waiting: connect and read timeouts as the ENS baseline sets them. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MAX_FETCH_TIME = Duration.ofSeconds(20);

    /** How long "could not find out" is reused: long enough to spare a failing responder a stampede. */
    private static final Duration UNKNOWN_CACHE = Duration.ofSeconds(30);

    /** What the check found, and where from or why not. */
    public record Answer(RevocationStatus status, String detail) {}

    private record Held(Answer answer, Instant until) {}

    private final EnsX509Properties settings;
    private final CrlStore crls;
    private final RevocationFetcher fetcher;
    private final CrlDirectory crlDirectory;
    private final Clock clock;
    private final Map<String, Held> answers = new ConcurrentHashMap<>();

    public SignInRevocation(
            EnsX509Properties settings,
            CrlStore crls,
            FetchPolicy policy,
            RevocationProperties revocation,
            Clock clock) {
        this.settings = settings;
        this.crls = crls;
        this.clock = clock;
        this.fetcher = new RevocationFetcher(
                policy, CONNECT_TIMEOUT, READ_TIMEOUT, MAX_FETCH_TIME, revocation.getMaxCrlBytes());
        this.crlDirectory = settings.getCrlDir() == null ? null : new CrlDirectory(Path.of(settings.getCrlDir()), clock);
    }

    /** Whether revocation is checked at sign-in at all. */
    public boolean isEnabled() {
        return settings.revocationMode() != EnsX509Properties.Revocation.OFF;
    }

    /**
     * @param issuer the certificate of whoever issued it, or null where none is known - in
     *     which case nothing can be asked
     */
    public Answer check(X509Certificate certificate, X509Certificate issuer) {
        EnsX509Properties.Revocation mode = settings.revocationMode();
        if (mode == EnsX509Properties.Revocation.OFF) {
            return new Answer(RevocationStatus.NOT_CHECKED, "revocation checking is off");
        }
        Instant now = Instant.now(clock);
        String key = certificate.getIssuerX500Principal().getName() + "#" + certificate.getSerialNumber().toString(16);
        Held held = answers.get(key);
        if (held != null && now.isBefore(held.until())) {
            return held.answer();
        }
        Answer answer = issuer == null
                ? new Answer(RevocationStatus.UNKNOWN, "no certificate of its issuer, "
                        + certificate.getIssuerX500Principal().getName() + ", to check it against")
                : ask(mode, certificate, issuer, now);
        Duration keep = answer.status() == RevocationStatus.UNKNOWN ? UNKNOWN_CACHE : settings.getRevocationCache();
        if (answers.size() > 50_000) {
            answers.clear();
        }
        answers.put(key, new Held(answer, now.plus(keep)));
        return answer;
    }

    private Answer ask(EnsX509Properties.Revocation mode, X509Certificate certificate, X509Certificate issuer, Instant now) {
        Answer fromResponder = null;
        if (mode.usesOcsp()) {
            fromResponder = overOcsp(certificate, issuer, now);
            if (fromResponder.status() != RevocationStatus.UNKNOWN || !mode.usesCrl()) {
                return fromResponder;
            }
        }
        Answer fromList = againstCrl(certificate, issuer, now);
        if (fromList.status() == RevocationStatus.UNKNOWN && fromResponder != null) {
            return new Answer(RevocationStatus.UNKNOWN, fromResponder.detail() + "; " + fromList.detail());
        }
        return fromList;
    }

    private Answer overOcsp(X509Certificate certificate, X509Certificate issuer, Instant now) {
        String responder = settings.getOcspResponderUrl() != null
                ? settings.getOcspResponderUrl()
                : RevocationEndpoints.of(certificate).ocspUrl();
        if (responder == null) {
            return new Answer(RevocationStatus.UNKNOWN, "the certificate names no OCSP responder");
        }
        RevocationChecker.Outcome outcome =
                OcspCheck.ask(fetcher, certificate, issuer, responder, "OCSP responder " + responder, Date.from(now));
        return new Answer(outcome.status(), outcome.detail());
    }

    private Answer againstCrl(X509Certificate certificate, X509Certificate issuer, Instant now) {
        X509CRL crl;
        String where;
        if (crlDirectory != null) {
            Optional<X509CRL> found = crlDirectory.latestFrom(issuer);
            if (found.isEmpty()) {
                return new Answer(RevocationStatus.UNKNOWN, "no CRL from "
                        + issuer.getSubjectX500Principal().getName() + " in " + crlDirectory.directory());
            }
            crl = found.get();
            where = "CRL in " + crlDirectory.directory();
        } else {
            List<String> urls = RevocationEndpoints.of(certificate).crlUrls();
            if (urls.isEmpty()) {
                return new Answer(RevocationStatus.UNKNOWN, "the certificate names no CRL distribution point");
            }
            Optional<CrlStore.Fetched> fetched = crls.fetch(urls, issuer, now, fetcher);
            if (fetched.isEmpty()) {
                return new Answer(RevocationStatus.UNKNOWN, "no distribution point answered: " + String.join(", ", urls));
            }
            if (!fetched.get().verified()) {
                // Sign-in never believes a list it cannot show is the issuer's own.
                return new Answer(RevocationStatus.UNKNOWN, "the CRL from " + fetched.get().url()
                        + " is not signed by the certificate's issuer");
            }
            crl = fetched.get().crl();
            where = "CRL from " + fetched.get().url();
        }
        if (crl.getNextUpdate() != null && now.isAfter(crl.getNextUpdate().toInstant())) {
            return new Answer(RevocationStatus.UNKNOWN,
                    where + " is out of date: its next update was due " + crl.getNextUpdate().toInstant());
        }
        BigInteger serial = certificate.getSerialNumber();
        X509CRLEntry entry = crl.getRevokedCertificate(serial);
        if (entry != null) {
            log.debug("{} lists serial {} as revoked", where, serial.toString(16));
            return new Answer(RevocationStatus.REVOKED, where);
        }
        return new Answer(RevocationStatus.GOOD, where);
    }

    /** Forgets every answer, for a test or an operator who has just published a revocation. */
    public void forget() {
        answers.clear();
    }
}

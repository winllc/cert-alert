package com.winllc.certalert.revocation;

import com.winllc.certalert.config.RevocationProperties;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.RevocationMethod;
import com.winllc.certalert.domain.RevocationStatus;
import java.math.BigInteger;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateRevokedException;
import java.security.cert.PKIXParameters;
import java.security.cert.PKIXRevocationChecker;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.security.cert.X509CRLEntry;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Asks whether a certificate has been revoked.
 *
 * <p>Two mechanisms, chosen by what the caller has rather than by preference. A CRL check
 * needs only what is already cached - the serial number and the distribution point - so it
 * is what the scheduled job over every entry can afford, and one download answers for every
 * certificate an authority issued. OCSP needs the certificate itself and its issuer's
 * certificate, and gives a fresher answer about that one certificate, so it is what a check
 * of a single entry asks with.
 *
 * <p>Nothing here ever answers {@link RevocationStatus#GOOD} because it failed to find out.
 * An unreachable responder, an unparseable list, a certificate that names nowhere to ask -
 * all of those are {@link RevocationStatus#UNKNOWN}, because an unanswered question is not
 * a negative answer, and treating it as one is how a revoked certificate stays in service.
 */
@Component
public class RevocationChecker {

    private static final Logger log = LoggerFactory.getLogger(RevocationChecker.class);

    private final CrlStore crls;
    private final IssuerCertificates issuers;
    private final RevocationProperties properties;

    /**
     * The platform's OCSP timeout is fifteen seconds and is read from a system property
     * once, the first time anything asks a responder anything. Left alone, a responder that
     * is not answering costs fifteen seconds per certificate - which on a directory of any
     * size is the difference between a job that finishes overnight and one that does not.
     *
     * <p>Set from the timeout this application was already configured with, and only where
     * nobody has set it themselves: an operator who passed {@code -Dcom.sun.security.ocsp
     * .timeout} meant it.
     */
    private static final String OCSP_TIMEOUT_PROPERTY = "com.sun.security.ocsp.timeout";

    public RevocationChecker(CrlStore crls, IssuerCertificates issuers, RevocationProperties properties) {
        this.crls = crls;
        this.issuers = issuers;
        this.properties = properties;

        if (System.getProperty(OCSP_TIMEOUT_PROPERTY) == null) {
            // Seconds, which is what the platform reads it as.
            long seconds = Math.max(1, properties.getReadTimeout().toSeconds());
            System.setProperty(OCSP_TIMEOUT_PROPERTY, Long.toString(seconds));
            log.debug("OCSP requests will time out after {}s", seconds);
        }
    }

    /**
     * What an authority said.
     *
     * @param revokedAt when the authority says it was revoked, where it says
     * @param reason the authority's reason, where it gives one
     * @param detail where the answer came from, or why there is none
     */
    public record Outcome(
            RevocationStatus status,
            RevocationMethod method,
            Instant revokedAt,
            String reason,
            String detail) {

        static Outcome unknown(String detail) {
            return new Outcome(RevocationStatus.UNKNOWN, null, null, null, detail);
        }

        static Outcome good(RevocationMethod method, String detail) {
            return new Outcome(RevocationStatus.GOOD, method, null, null, detail);
        }

        static Outcome revoked(RevocationMethod method, Instant at, String reason, String detail) {
            return new Outcome(RevocationStatus.REVOKED, method, at, reason, detail);
        }

        public boolean isRevoked() {
            return status == RevocationStatus.REVOKED;
        }
    }

    /**
     * From the cache alone, which means the certificate's own revocation list.
     *
     * <p>What the scheduled job uses: it holds a hundred thousand certificates and a
     * handful of authorities, and this turns that into a handful of downloads.
     */
    public Outcome check(CachedCertificate certificate, Instant now) {
        return checkAgainstCrl(certificate, now);
    }

    /**
     * With the certificate itself in hand, which makes OCSP possible.
     *
     * <p>Preferred where it works, because it is an answer about this certificate now
     * rather than a list published at some point before now. It needs two things the cache
     * does not hold: the issuer's certificate, since OCSP names a certificate by hashes of
     * its issuer's name and key and the leaf carries neither, and the leaf's own bytes.
     * Without either it falls through to the list - and where the list has nothing to say
     * either, the result says which piece was missing rather than blaming the certificate
     * for naming no distribution point.
     *
     * @param leaf the certificate itself, or null where it could not be read back
     */
    public Outcome check(CachedCertificate certificate, X509Certificate leaf, Instant now) {
        Optional<X509Certificate> issuer = issuers.issuerOf(certificate);
        String responder = responderFor(certificate, properties.getDefaultOcspUrl());
        if (responder != null && issuer.isPresent() && leaf != null) {
            Outcome outcome = checkOverOcsp(certificate, leaf, issuer.get(), responder);
            // A responder that answered settles it; one that did not is not an answer, and
            // the list may still have one.
            if (outcome.status() != RevocationStatus.UNKNOWN) {
                return outcome;
            }
            Outcome fromList = checkAgainstCrl(certificate, now);
            return fromList.status() == RevocationStatus.UNKNOWN ? outcome : fromList;
        }

        Outcome fromList = checkAgainstCrl(certificate, now);
        if (fromList.status() != RevocationStatus.UNKNOWN || responder == null) {
            return fromList;
        }
        // There is a responder to ask and it was not asked. Saying so is the difference
        // between a deployment that knows what to fix and one that reads "names no CRL
        // distribution point" against a certificate whose responder it configured itself.
        return Outcome.unknown(whyOcspWasNotAsked(responder, issuer.isPresent(), leaf != null));
    }

    private static String whyOcspWasNotAsked(String responder, boolean haveIssuer, boolean haveLeaf) {
        if (!haveIssuer) {
            return "OCSP at " + responder + " needs the issuer's certificate; none is configured for this one";
        }
        if (!haveLeaf) {
            return "OCSP at " + responder + " needs the certificate itself, which could not be read back";
        }
        return "OCSP at " + responder + " could not be asked";
    }

    /**
     * Whether a responder is the only way to answer for this certificate.
     *
     * <p>Asked by the scheduled check before it pays for the certificate's bytes, which
     * means a read of the entry that publishes it. Three things have to be true: there is
     * a responder to ask, there is an issuer certificate to name the certificate by, and
     * there is no revocation list to use instead.
     *
     * <p>That last one keeps the job affordable. A list is one download that answers for
     * every certificate an authority issued; a responder is one request per certificate,
     * plus one directory read per entry to form it. On a hundred thousand entries that is
     * the difference between six downloads and a quarter of a million round trips - so the
     * list is used wherever there is one, and the responder is what answers for the
     * authorities that publish neither address.
     */
    public boolean onlyAResponderCanAnswer(CachedCertificate certificate) {
        return distributionPointsFor(certificate, properties.getDefaultCrlUrl()).isEmpty()
                && responderFor(certificate, properties.getDefaultOcspUrl()) != null
                && issuers.issuerOf(certificate).isPresent();
    }

    // -------------------------------------------------------------------------------------
    // The list
    // -------------------------------------------------------------------------------------

    private Outcome checkAgainstCrl(CachedCertificate certificate, Instant now) {
        List<String> urls = distributionPointsFor(certificate, properties.getDefaultCrlUrl());
        if (urls.isEmpty()) {
            return Outcome.unknown("The certificate names no CRL distribution point");
        }
        BigInteger serial = serialOf(certificate);
        if (serial == null) {
            return Outcome.unknown("The cached serial number could not be read");
        }
        Optional<CrlStore.Fetched> fetched =
                crls.fetch(urls, issuers.issuerOf(certificate).orElse(null), now);
        if (fetched.isEmpty()) {
            return Outcome.unknown("No distribution point answered: " + String.join(", ", urls));
        }
        CrlStore.Fetched crl = fetched.get();
        if (!crl.verified() && !properties.isAllowUnverifiedCrl()) {
            return Outcome.unknown(
                    "The CRL from " + crl.url() + " could not be verified against a known issuer");
        }
        String where = "CRL from " + crl.url()
                + (certificate.getCrlUrls().isEmpty() ? " (configured default)" : "")
                + (crl.verified() ? "" : " (signature not verified)");

        X509CRLEntry entry = crl.crl().getRevokedCertificate(serial);
        if (entry == null) {
            return Outcome.good(RevocationMethod.CRL, where);
        }
        return Outcome.revoked(
                RevocationMethod.CRL,
                entry.getRevocationDate() == null ? null : entry.getRevocationDate().toInstant(),
                entry.getRevocationReason() == null ? null : entry.getRevocationReason().name(),
                where);
    }

    /** Stored as hexadecimal, which is how a serial is read out and quoted. */
    private static BigInteger serialOf(CachedCertificate certificate) {
        String serial = certificate.getSerialNumber();
        if (serial == null || serial.isBlank()) {
            return null;
        }
        try {
            return new BigInteger(serial.trim(), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------------------
    // The responder
    // -------------------------------------------------------------------------------------

    /**
     * One question to the responder the certificate names.
     *
     * <p>Run through the platform's path validator rather than by building the request by
     * hand: the request is a signed-response protocol with its own ASN.1, and a
     * hand-rolled one that gets the issuer hashes subtly wrong would answer "unknown"
     * forever without ever looking wrong.
     *
     * <p>The path is the leaf alone, anchored on its issuer, and the checker is told to
     * look at the end entity only and not to fall back to a list - the caller does that
     * itself, so that the list it uses is the one it has already cached.
     */
    private Outcome checkOverOcsp(
            CachedCertificate certificate, X509Certificate leaf, X509Certificate issuer, String responder) {
        try {
            CertPathValidator validator = CertPathValidator.getInstance("PKIX");
            PKIXRevocationChecker checker = (PKIXRevocationChecker) validator.getRevocationChecker();
            checker.setOptions(EnumSet.of(
                    PKIXRevocationChecker.Option.ONLY_END_ENTITY, PKIXRevocationChecker.Option.NO_FALLBACK));
            checker.setOcspResponder(java.net.URI.create(responder));

            PKIXParameters parameters = new PKIXParameters(Set.of(new TrustAnchor(issuer, null)));
            // The explicit checker does the asking; the built-in one would ask again.
            parameters.setRevocationEnabled(false);
            parameters.addCertPathChecker(checker);

            CertPath path = CertificateFactory.getInstance("X.509").generateCertPath(List.of(leaf));
            validator.validate(path, parameters);
            return Outcome.good(RevocationMethod.OCSP, describeResponder(certificate, responder));
        } catch (CertPathValidatorException e) {
            return fromValidationFailure(certificate, e, responder);
        } catch (java.security.GeneralSecurityException | RuntimeException e) {
            log.debug("OCSP check failed for {}", certificate.getSha256Fingerprint(), e);
            return Outcome.unknown("OCSP could not be asked: " + describe(e));
        }
    }

    /**
     * A validation failure is not always a revocation. The path is one certificate under
     * its own issuer, so the other ways it can fail - the wrong issuer, an expired leaf -
     * mean the question went unanswered rather than answered badly.
     */
    private Outcome fromValidationFailure(
            CachedCertificate certificate, CertPathValidatorException e, String responder) {
        if (e.getCause() instanceof CertificateRevokedException revoked) {
            return Outcome.revoked(
                    RevocationMethod.OCSP,
                    revoked.getRevocationDate().toInstant(),
                    revoked.getRevocationReason() == null ? null : revoked.getRevocationReason().name(),
                    describeResponder(certificate, responder));
        }
        if (e.getReason() == CertPathValidatorException.BasicReason.REVOKED) {
            return Outcome.revoked(
                    RevocationMethod.OCSP, null, null, describeResponder(certificate, responder));
        }
        return Outcome.unknown("OCSP gave no usable answer: " + describe(e));
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /**
     * Which responder to ask about this certificate.
     *
     * <p>The one the certificate names, where it names one: the issuer saying where to ask
     * outranks anything configured here, and a deployment reading more than one authority
     * has at most one configured address that is right. Otherwise the configured fallback,
     * for the internal CAs that leave the extension out because everything that will ever
     * validate the certificate already knows where to go.
     */
    static String responderFor(CachedCertificate certificate, String configuredDefault) {
        String named = certificate.getOcspUrl();
        if (named != null && !named.isBlank()) {
            return named;
        }
        return configuredDefault == null || configuredDefault.isBlank() ? null : configuredDefault.trim();
    }

    /**
     * Where to fetch this certificate's revocation list.
     *
     * <p>The points it names, in its own order, where it names any. Otherwise the
     * configured fallback, for the internal CAs that leave the extension out - the same
     * bargain as the default responder, and the cheaper one to take: a list is one download
     * that answers for every certificate the authority issued, where a responder is one
     * request each. A list that turns out to be some other authority's fails its signature
     * check and counts as no answer, so a wrong guess here cannot read as a good one.
     */
    static List<String> distributionPointsFor(CachedCertificate certificate, String configuredDefault) {
        List<String> named = certificate.getCrlUrls();
        if (!named.isEmpty()) {
            return named;
        }
        return configuredDefault == null || configuredDefault.isBlank()
                ? List.of()
                : List.of(configuredDefault.trim());
    }

    /**
     * Where the answer came from. A result that came back from the configured fallback says
     * so, because "the responder said it is good" means something different when the
     * certificate never named that responder.
     */
    static String describeResponder(CachedCertificate certificate, String responder) {
        boolean named = certificate.getOcspUrl() != null && !certificate.getOcspUrl().isBlank();
        return "OCSP responder " + responder + (named ? "" : " (configured default)");
    }

    /** Whether a responder could be asked at all, for a status the UI can show. */
    public boolean ocspPossible() {
        return !issuers.isEmpty();
    }
}

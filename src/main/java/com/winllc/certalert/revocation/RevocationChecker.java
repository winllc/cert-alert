package com.winllc.certalert.revocation;

import com.winllc.certalert.config.RevocationProperties;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.RevocationMethod;
import com.winllc.certalert.domain.RevocationStatus;
import java.math.BigInteger;
import java.security.cert.X509CRL;
import java.security.cert.X509CRLEntry;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
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

    public RevocationChecker(CrlStore crls, IssuerCertificates issuers, RevocationProperties properties) {
        this.crls = crls;
        this.issuers = issuers;
        this.properties = properties;
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
     * One question to the responder the certificate names, asked through the same fetcher
     * as the lists - so it is held to the same addresses and timeouts - and answered through
     * the platform's path validator. See {@link OcspCheck}.
     */
    private Outcome checkOverOcsp(
            CachedCertificate certificate, X509Certificate leaf, X509Certificate issuer, String responder) {
        Outcome outcome = OcspCheck.ask(
                crls.fetcher(), leaf, issuer, responder, describeResponder(certificate, responder), new Date());
        if (outcome.status() == RevocationStatus.UNKNOWN) {
            log.debug("OCSP check for {} at {}: {}", certificate.getSha256Fingerprint(), responder, outcome.detail());
        }
        return outcome;
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

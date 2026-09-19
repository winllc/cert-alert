package com.winllc.certalert.service;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateUse;
import com.winllc.certalert.domain.CredentialIssue;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Which of an entry's certificates are the current ones.
 *
 * <p>Harder than "the newest" for a person, because a person does not have one certificate.
 * PKI for people issues two at a time: a signing certificate, whose private key must never
 * be copied, and a key encipherment certificate, whose private key usually must be escrowed
 * so that what was encrypted to somebody who has left can still be read. Two keys, two
 * certificates, one issuance - and anything that asks "is this entry on its latest
 * certificate" has to mean both of them.
 *
 * <p>So the current credentials are the newest certificate of each use, not the newest
 * certificate. That framing also makes the failure visible: where only one of the two was
 * renewed, the pair is left straddling two issuances, and {@link CredentialIssue} says so.
 *
 * <p>A server is the simple case of the same rule. Its certificate normally carries both
 * bits, so the signing and encryption halves are the same certificate and the set has one
 * member.
 *
 * <p>Pure: no Spring, no repository, no clock of its own. Everything it needs is passed in,
 * which is what lets it be tested with certificates rather than with a database.
 */
public final class CertificateIssuance {

    private CertificateIssuance() {}

    /**
     * What an entry currently holds, and what is wrong with it.
     *
     * @param signing the newest unexpired certificate that signs, or null
     * @param encryption the newest unexpired certificate that is encrypted to, or null
     * @param current both of those, newest first and without repeating a certificate that
     *     does both; empty where everything has expired
     * @param issues what is wrong with the set, empty where nothing is
     */
    public record Credentials(
            CachedCertificate signing,
            CachedCertificate encryption,
            List<CachedCertificate> current,
            Set<CredentialIssue> issues) {

        public static final Credentials NONE =
                new Credentials(null, null, List.of(), Set.of());

        public boolean isEmpty() {
            return current.isEmpty();
        }

        public boolean hasIssues() {
            return !issues.isEmpty();
        }

        /** Whether this certificate is one of the current ones. */
        public boolean holds(CachedCertificate certificate) {
            return certificate != null
                    && current.stream().anyMatch(held -> held.getId() != null
                            ? held.getId().equals(certificate.getId())
                            : held == certificate);
        }

        /**
         * How far apart the pair was issued, or null where there is no pair - one half
         * missing, or one certificate doing both jobs.
         */
        public Duration issuedApart() {
            if (signing == null || encryption == null || signing == encryption) {
                return null;
            }
            if (signing.getNotBefore() == null || encryption.getNotBefore() == null) {
                return null;
            }
            return Duration.between(signing.getNotBefore(), encryption.getNotBefore()).abs();
        }
    }

    /**
     * The single most recently issued certificate, which is the right question to ask of a
     * server: one endpoint presents one certificate, and this is the one it should be.
     *
     * <p>Expired certificates count here. "The newest the directory publishes" is a fact
     * about the directory, and an endpoint serving an expired certificate that is
     * nonetheless the newest one published is a different problem from an endpoint serving
     * something the directory has never heard of.
     */
    public static Optional<CachedCertificate> mostRecentlyIssued(Collection<CachedCertificate> certificates) {
        if (certificates == null) {
            return Optional.empty();
        }
        return certificates.stream()
                .filter(certificate -> certificate.getNotBefore() != null)
                .max(byIssuance());
    }

    /**
     * The certificates an entry is currently working with.
     *
     * @param certificates everything cached for the entry
     * @param pairWindow how far apart two may be issued and still be one issuance
     * @param now what counts as expired
     */
    public static Credentials current(
            Collection<CachedCertificate> certificates, Duration pairWindow, Instant now) {

        if (certificates == null || certificates.isEmpty()) {
            return Credentials.NONE;
        }
        // Expired certificates are not what the entry is working with, and a person whose
        // whole pair has lapsed is already being reported on for exactly that. Reading the
        // dates rather than the cached status keeps this honest between refreshes.
        List<CachedCertificate> live = certificates.stream()
                .filter(certificate -> certificate.getNotAfter() != null && certificate.getNotAfter().isAfter(now))
                .sorted(byIssuance().reversed())
                .toList();
        if (live.isEmpty()) {
            return Credentials.NONE;
        }

        // Nothing was cached with a key usage extension, so which is which is not something
        // this can answer yet - and reporting both halves missing would be worse than
        // saying so. The newest is the best available answer to "the current one".
        if (live.stream().allMatch(certificate -> certificate.getUse() == CertificateUse.UNSPECIFIED)) {
            return new Credentials(
                    null, null, List.of(live.getFirst()), EnumSet.of(CredentialIssue.USE_NOT_KNOWN));
        }

        CachedCertificate signing = newestWhere(live, use -> use.signs());
        CachedCertificate encryption = newestWhere(live, use -> use.encrypts());

        Set<CredentialIssue> issues = EnumSet.noneOf(CredentialIssue.class);
        if (signing == null) {
            issues.add(CredentialIssue.MISSING_SIGNING);
        }
        if (encryption == null) {
            issues.add(CredentialIssue.MISSING_ENCRYPTION);
        }

        // De-duplicated by identity: one certificate carrying both bits is one certificate,
        // and it is the same row read once, so it is the same object.
        List<CachedCertificate> held = Stream.of(signing, encryption)
                .filter(Objects::nonNull)
                .distinct()
                .sorted(byIssuance().reversed())
                .toList();

        Credentials credentials = new Credentials(signing, encryption, held, issues);
        Duration apart = credentials.issuedApart();
        if (apart != null && pairWindow != null && apart.compareTo(pairWindow) > 0) {
            issues.add(CredentialIssue.ISSUED_APART);
        }
        return credentials;
    }

    private static CachedCertificate newestWhere(
            List<CachedCertificate> newestFirst, java.util.function.Predicate<CertificateUse> wanted) {

        for (CachedCertificate certificate : newestFirst) {
            if (wanted.test(certificate.getUse())) {
                return certificate;
            }
        }
        return null;
    }

    /**
     * Oldest first. Issuance date decides; the expiry date and then the fingerprint break
     * ties, so two certificates issued in the same second still have a stable order rather
     * than whichever one the database happened to return first.
     */
    private static Comparator<CachedCertificate> byIssuance() {
        return Comparator.comparing(CachedCertificate::getNotBefore, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(CachedCertificate::getNotAfter, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(
                        CachedCertificate::getSha256Fingerprint,
                        Comparator.nullsFirst(Comparator.naturalOrder()));
    }

    /** Everything an entry holds that is not one of its current certificates. */
    public static List<CachedCertificate> superseded(
            Collection<CachedCertificate> certificates, Credentials credentials) {

        List<CachedCertificate> rest = new ArrayList<>();
        for (CachedCertificate certificate : certificates) {
            if (!credentials.holds(certificate)) {
                rest.add(certificate);
            }
        }
        return rest;
    }
}

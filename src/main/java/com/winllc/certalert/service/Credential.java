package com.winllc.certalert.service;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateUse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * One credential: the certificates a person actually has to renew together.
 *
 * <p>PKI for people issues two at a time. A signing certificate, whose private key must
 * never be copied, and a key encipherment certificate, whose private key is usually
 * escrowed so that what was encrypted to somebody who has left can still be read. Two
 * keys, two certificates, one issuance - and one thing to do about them.
 *
 * <p>So the round-up counts credentials rather than certificates. Telling somebody "2
 * certificates are expiring" when it is one credential expiring reads as twice the work,
 * and the second line says the same thing as the first with a different serial number. A
 * person with a pair and a server has two things to do, not three.
 *
 * <p>Two certificates are one credential when they carry the same subject, one signs and
 * the other is encrypted to, and they were issued within
 * {@code cert-alert.credentials.pair-window} of each other - which is the question "was
 * this one renewal or two" with a week of slack on it. Anything else stands alone: a
 * server's certificate does both jobs and is a credential by itself, and so is a
 * certificate whose other half was never published.
 */
final class Credential {

    /** Signing half first where there is one, because that is how the pair is spoken of. */
    private final List<CachedCertificate> certificates;

    private Credential(List<CachedCertificate> certificates) {
        this.certificates = List.copyOf(certificates);
    }

    /**
     * Gathers certificates into the credentials they belong to.
     *
     * <p>One pass, in the order given, so a round-up still reads soonest-to-expire first
     * if it was handed them that way: each certificate either joins the credential of one
     * already placed, or opens its own.
     */
    static List<Credential> group(Collection<CachedCertificate> all, Duration pairWindow) {
        Set<CachedCertificate> taken = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Credential> credentials = new ArrayList<>();

        for (CachedCertificate certificate : all) {
            if (taken.contains(certificate)) {
                continue;
            }
            taken.add(certificate);
            CachedCertificate other = otherHalfOf(certificate, all, taken, pairWindow);
            if (other == null) {
                credentials.add(new Credential(List.of(certificate)));
                continue;
            }
            taken.add(other);
            credentials.add(new Credential(certificate.getUse() == CertificateUse.SIGNING
                    ? List.of(certificate, other)
                    : List.of(other, certificate)));
        }
        return List.copyOf(credentials);
    }

    /**
     * The other half of this certificate's pair, or null.
     *
     * <p>The nearest one in time rather than the first found: somebody who has held the
     * same name for years has several of each, and pairing a certificate with another
     * year's other half would report a credential that never existed.
     */
    private static CachedCertificate otherHalfOf(
            CachedCertificate certificate,
            Collection<CachedCertificate> all,
            Set<CachedCertificate> taken,
            Duration pairWindow) {

        if (!pairable(certificate) || certificate.getNotBefore() == null) {
            return null;
        }
        CertificateUse wanted = certificate.getUse() == CertificateUse.SIGNING
                ? CertificateUse.ENCRYPTION
                : CertificateUse.SIGNING;
        String subject = normalise(certificate.getSubjectDn());
        Instant issued = certificate.getNotBefore();

        return all.stream()
                .filter(candidate -> !taken.contains(candidate))
                .filter(candidate -> candidate.getUse() == wanted)
                .filter(candidate -> subject.equals(normalise(candidate.getSubjectDn())))
                .filter(candidate -> candidate.getNotBefore() != null
                        && within(issued, candidate.getNotBefore(), pairWindow))
                .min(Comparator.comparing(candidate -> apart(issued, candidate.getNotBefore())))
                .orElse(null);
    }

    private static boolean within(Instant one, Instant other, Duration window) {
        return apart(one, other).compareTo(window) <= 0;
    }

    private static Duration apart(Instant one, Instant other) {
        return Duration.between(one, other).abs();
    }

    /** Only the two halves of a person's pair pair up; everything else is whole already. */
    private static boolean pairable(CachedCertificate certificate) {
        CertificateUse use = certificate.getUse();
        return (use == CertificateUse.SIGNING || use == CertificateUse.ENCRYPTION)
                && normalise(certificate.getSubjectDn()) != null;
    }

    private static String normalise(String subjectDn) {
        return subjectDn == null || subjectDn.isBlank() ? null : subjectDn.trim().toLowerCase(Locale.ROOT);
    }

    List<CachedCertificate> certificates() {
        return certificates;
    }

    /** The certificate the credential is described by: the signing half of a pair. */
    CachedCertificate first() {
        return certificates.getFirst();
    }

    boolean isPair() {
        return certificates.size() > 1;
    }

    /**
     * When the credential stops working, which is when its soonest half does. Renewing one
     * half and not the other leaves a person able to sign and not to read, or the reverse,
     * and the date that matters is the first one.
     */
    Instant expiresAt() {
        return certificates.stream()
                .map(CachedCertificate::getNotAfter)
                .filter(Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(null);
    }

    /** "Signing and encryption", or whichever single use it is. */
    String useSummary() {
        if (!isPair()) {
            CertificateUse use = first().getUse();
            return use == CertificateUse.UNSPECIFIED || use == CertificateUse.OTHER ? "" : use.label();
        }
        return "Signing and encryption";
    }
}

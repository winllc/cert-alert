package com.winllc.certalert.service;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.CertificateUse;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;

/**
 * Whether an entry has already published something in a certificate's place.
 *
 * <p>The question a round-up has to answer before writing to anybody: is this still their
 * problem? A certificate that has been renewed is not, and going on about it for the weeks
 * until the old one lapses is how a round-up teaches people to ignore it.
 *
 * <p>A renewal is the <em>same name, for the same job, issued again</em>. That is what a CA
 * does when it renews: same identity, same use, new dates, new key. So a certificate is
 * replaced when the entry holds another one with the same subject and the same
 * {@link CertificateUse}, issued later, that has not itself expired.
 *
 * <p>Both halves of that matter, and each rules out a way of going wrong.
 *
 * <p><strong>Same subject</strong>, because an entry may publish certificates for several
 * names. "The newest one wins" would stop telling somebody about a credential that is
 * genuinely running out on the strength of an unrelated certificate being younger.
 *
 * <p><strong>Same use</strong>, because a person does not hold one certificate. PKI for
 * people issues two to the same name in the same breath - one that signs, one that is
 * encrypted to - and the second is written to the directory moments after the first. On
 * subject alone the encryption half would look like a renewal of the signing half, which is
 * to say that on a directory where every person holds a pair, half of everything expiring
 * would go unreported. Renewing one half replaces that half and leaves the other standing,
 * which is also the case worth hearing about: a pair straddling two issuances is the
 * ordinary way to end up half expired.
 *
 * <p>Of the two ways to be wrong, silence about a certificate nobody has renewed is much
 * the worse one, so every comparison here fails towards saying something.
 */
final class Renewals {

    private Renewals() {}

    /**
     * Whether something newer with the same subject is standing in this one's place.
     *
     * @param certificate the one being asked about
     * @param held everything the entry publishes, the certificate itself included
     */
    static boolean replaced(CachedCertificate certificate, Collection<CachedCertificate> held) {
        String subject = normalise(certificate.getSubjectDn());
        if (subject == null) {
            // Nothing to match on. Unreplaceable rather than replaced: saying nothing about
            // a certificate because it has no subject would be the wrong way round.
            return false;
        }
        CertificateUse use = certificate.getUse();
        Instant issued = certificate.getNotBefore();
        for (CachedCertificate other : held) {
            if (other == certificate || other.getStatus() == CertificateStatus.EXPIRED) {
                continue;
            }
            if (!subject.equals(normalise(other.getSubjectDn())) || other.getUse() != use) {
                continue;
            }
            if (issued == null || other.getNotBefore() == null) {
                // Without both dates there is no telling which came first, and guessing
                // wrong means silence about the one still in use.
                continue;
            }
            if (other.getNotBefore().isAfter(issued)) {
                return true;
            }
        }
        return false;
    }

    private static String normalise(String subjectDn) {
        if (subjectDn == null || subjectDn.isBlank()) {
            return null;
        }
        return subjectDn.trim().toLowerCase(Locale.ROOT);
    }
}

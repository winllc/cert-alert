package com.winllc.certalert.service;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
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
 * <p>A renewal is the <em>same subject, issued again</em>. That is what a CA does when it
 * renews: same identity, new dates, new key. So a certificate is replaced when the entry
 * holds another one with the same subject, issued later, that has not itself expired.
 *
 * <p>Deliberately not "the newest one wins". A person holds two certificates at once - a
 * signing certificate and a key encipherment one - and an entry may publish certificates
 * for several names besides. Treating the newest as replacing the rest would stop telling
 * somebody about a credential that is genuinely running out, on the strength of an
 * unrelated certificate being younger. Of the two ways to be wrong, silence about a
 * certificate nobody has renewed is the worse one.
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
        Instant issued = certificate.getNotBefore();
        for (CachedCertificate other : held) {
            if (other == certificate || other.getStatus() == CertificateStatus.EXPIRED) {
                continue;
            }
            if (!subject.equals(normalise(other.getSubjectDn()))) {
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

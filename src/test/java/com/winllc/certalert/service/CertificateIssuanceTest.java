package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.config.RiskProperties;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateUse;
import com.winllc.certalert.domain.CredentialIssue;
import com.winllc.certalert.support.TestCertificates;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which certificates an entry is actually working with.
 *
 * <p>The case that matters is a person, who holds two at once: a signing certificate whose
 * key is never escrowed and a key encipherment certificate whose key usually is. "The most
 * recent one" is the wrong question about them, and asking it is how half a renewal goes
 * unnoticed.
 *
 * <p>Built from real certificates rather than stubs, because what tells the two apart is
 * the key usage extension, and the point is that it is read correctly.
 */
class CertificateIssuanceTest {

    private static final Duration WEEK = Duration.ofDays(7);
    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");

    private final CertificateParser parser = new CertificateParser(new RiskProperties());

    @Test
    void aPersonsPairIsBothCertificatesRatherThanTheNewer() {
        // Issued minutes apart by the same CA run, as a real pair is.
        CachedCertificate signing = parse(TestCertificates.signing(
                "Ada Lovelace", NOW.minus(Duration.ofDays(30)), NOW.plus(Duration.ofDays(335))));
        CachedCertificate encryption = parse(TestCertificates.encryption(
                "Ada Lovelace", NOW.minus(Duration.ofDays(30)).plusSeconds(180), NOW.plus(Duration.ofDays(335))));

        CertificateIssuance.Credentials credentials =
                CertificateIssuance.current(List.of(signing, encryption), WEEK, NOW);

        assertThat(credentials.current()).hasSize(2);
        assertThat(credentials.signing().getUse()).isEqualTo(CertificateUse.SIGNING);
        assertThat(credentials.encryption().getUse()).isEqualTo(CertificateUse.ENCRYPTION);
        assertThat(credentials.issues()).isEmpty();
        assertThat(credentials.issuedApart()).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void theOlderPairIsSupersededWhenBothAreRenewed() {
        CachedCertificate oldSigning = parse(TestCertificates.signing(
                "Ada", NOW.minus(Duration.ofDays(400)), NOW.plus(Duration.ofDays(30))));
        CachedCertificate oldEncryption = parse(TestCertificates.encryption(
                "Ada", NOW.minus(Duration.ofDays(400)), NOW.plus(Duration.ofDays(30))));
        CachedCertificate newSigning = parse(TestCertificates.signing(
                "Ada", NOW.minus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(363))));
        CachedCertificate newEncryption = parse(TestCertificates.encryption(
                "Ada", NOW.minus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(363))));
        List<CachedCertificate> all = List.of(oldSigning, oldEncryption, newSigning, newEncryption);

        CertificateIssuance.Credentials credentials = CertificateIssuance.current(all, WEEK, NOW);

        // Both halves of the new pair, and neither half of the old one - although the old
        // pair is still valid, so an expiry-driven answer would have included it.
        assertThat(credentials.current()).containsExactlyInAnyOrder(newSigning, newEncryption);
        assertThat(credentials.issues()).isEmpty();
        assertThat(CertificateIssuance.superseded(all, credentials))
                .containsExactlyInAnyOrder(oldSigning, oldEncryption);
    }

    @Test
    void halfARenewalIsWhatTheWindowIsFor() {
        // The signing certificate was renewed in June; the encryption one is still the one
        // from last year. Both are valid, so nothing about either says anything is wrong.
        CachedCertificate signing = parse(TestCertificates.signing(
                "Grace", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(364))));
        CachedCertificate encryption = parse(TestCertificates.encryption(
                "Grace", NOW.minus(Duration.ofDays(300)), NOW.plus(Duration.ofDays(65))));

        CertificateIssuance.Credentials credentials =
                CertificateIssuance.current(List.of(signing, encryption), WEEK, NOW);

        assertThat(credentials.issues()).containsExactly(CredentialIssue.ISSUED_APART);
        // Still both: they are what the person is working with, wrong-looking or not.
        assertThat(credentials.current()).containsExactlyInAnyOrder(signing, encryption);
        assertThat(credentials.issuedApart()).isGreaterThan(Duration.ofDays(298));
    }

    @Test
    void aMissingHalfIsNamedRatherThanCountedAsThePair() {
        CachedCertificate signing = parse(TestCertificates.signing(
                "Solo", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(364))));

        CertificateIssuance.Credentials credentials =
                CertificateIssuance.current(List.of(signing), WEEK, NOW);

        assertThat(credentials.issues()).containsExactly(CredentialIssue.MISSING_ENCRYPTION);
        assertThat(credentials.encryption()).isNull();
        // Not an issued-apart as well: there is no pair to be apart.
        assertThat(credentials.issuedApart()).isNull();
    }

    @Test
    void anExpiredHalfCountsAsMissingRatherThanCurrent() {
        CachedCertificate signing = parse(TestCertificates.signing(
                "Lapsed", NOW.minus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(363))));
        CachedCertificate encryption = parse(TestCertificates.encryption(
                "Lapsed", NOW.minus(Duration.ofDays(400)), NOW.minus(Duration.ofDays(1))));

        CertificateIssuance.Credentials credentials =
                CertificateIssuance.current(List.of(signing, encryption), WEEK, NOW);

        assertThat(credentials.issues()).containsExactly(CredentialIssue.MISSING_ENCRYPTION);
        assertThat(credentials.current()).containsExactly(signing);
    }

    @Test
    void aServersOneCertificateDoesBothJobs() {
        CachedCertificate certificate = parse(TestCertificates.dual(
                "app.example.gov", NOW.minus(Duration.ofDays(10)), NOW.plus(Duration.ofDays(355))));

        CertificateIssuance.Credentials credentials =
                CertificateIssuance.current(List.of(certificate), WEEK, NOW);

        assertThat(certificate.getUse()).isEqualTo(CertificateUse.DUAL);
        // One certificate, counted once, filling both roles - so nothing is missing.
        assertThat(credentials.current()).containsExactly(certificate);
        assertThat(credentials.signing()).isSameAs(credentials.encryption());
        assertThat(credentials.issues()).isEmpty();
        assertThat(credentials.issuedApart()).isNull();
    }

    @Test
    void certificatesCachedBeforeKeyUsageWasReadSaySoRatherThanLookingBroken() {
        // What a row written by an earlier version looks like: parsed, but with no use.
        CachedCertificate older = parse(TestCertificates.der(
                "Legacy", NOW.minus(Duration.ofDays(5)), NOW.plus(Duration.ofDays(360))));
        older.describeKeyUsage(List.of());

        CertificateIssuance.Credentials credentials =
                CertificateIssuance.current(List.of(older), WEEK, NOW);

        assertThat(credentials.issues()).containsExactly(CredentialIssue.USE_NOT_KNOWN);
        // Not "both halves missing", which is what a naive reading would have said.
        assertThat(credentials.issues()).doesNotContain(CredentialIssue.MISSING_SIGNING);
        assertThat(credentials.current()).containsExactly(older);
    }

    @Test
    void anEntryWhoseCertificatesHaveAllExpiredHoldsNothingCurrent() {
        CachedCertificate expired = parse(TestCertificates.signing(
                "Gone", NOW.minus(Duration.ofDays(400)), NOW.minus(Duration.ofDays(1))));

        CertificateIssuance.Credentials credentials =
                CertificateIssuance.current(List.of(expired), WEEK, NOW);

        assertThat(credentials.isEmpty()).isTrue();
        // Nothing to report: the expiry reporting is already saying it, and a missing-half
        // badge on top of an expired badge tells nobody anything new.
        assertThat(credentials.issues()).isEmpty();
    }

    @Test
    void theMostRecentlyIssuedIsTheOneAServerShouldBePresenting() {
        CachedCertificate older = parse(TestCertificates.dual(
                "app", NOW.minus(Duration.ofDays(300)), NOW.plus(Duration.ofDays(65))));
        CachedCertificate newer = parse(TestCertificates.dual(
                "app", NOW.minus(Duration.ofDays(3)), NOW.plus(Duration.ofDays(362))));

        assertThat(CertificateIssuance.mostRecentlyIssued(List.of(older, newer))).contains(newer);
        assertThat(CertificateIssuance.mostRecentlyIssued(List.of())).isEmpty();
    }

    private CachedCertificate parse(byte[] der) {
        return parser.parse(der, NOW);
    }
}

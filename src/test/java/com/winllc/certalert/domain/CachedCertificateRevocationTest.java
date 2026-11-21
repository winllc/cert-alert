package com.winllc.certalert.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** What a revocation check leaves on a cached certificate. */
class CachedCertificateRevocationTest {

    private static final Instant NOW = Instant.parse("2026-06-01T05:30:00Z");

    private static CachedCertificate certificate() {
        return new CachedCertificate("ab", "01", "CN=x", "CN=ca", NOW.minus(Duration.ofDays(10)),
                NOW.plus(Duration.ofDays(355)), "SHA256withRSA", "SHA-256", "RSA", 2048, null, NOW);
    }

    private static void revoked(CachedCertificate certificate, Instant checkedAt) {
        certificate.recordRevocation(RevocationStatus.REVOKED, RevocationMethod.CRL,
                NOW.minus(Duration.ofDays(40)), "keyCompromise", "CRL from http://crl.example/ca.crl", checkedAt);
    }

    @Test
    void anUnansweredCheckDoesNotUndoARevocation() {
        CachedCertificate certificate = certificate();
        revoked(certificate, NOW.minus(Duration.ofDays(1)));

        certificate.recordRevocation(RevocationStatus.UNKNOWN, null, null, null, "CRL host unreachable", NOW);

        assertThat(certificate.getRevocationStatus()).isEqualTo(RevocationStatus.REVOKED);
        assertThat(certificate.getRevokedAt()).isEqualTo(NOW.minus(Duration.ofDays(40)));
        assertThat(certificate.getRevocationReason()).isEqualTo("keyCompromise");
        assertThat(certificate.getRevocationMethod()).isEqualTo(RevocationMethod.CRL);
        assertThat(certificate.getRevocationDetail()).contains("CRL host unreachable");
        assertThat(certificate.getRevocationCheckedAt()).isEqualTo(NOW);
        assertThat(certificate.getRevocationFoundAt()).isEqualTo(NOW.minus(Duration.ofDays(1)));
    }

    @Test
    void whenItWasFoundRevokedStaysPutThroughNightlyChecks() {
        CachedCertificate certificate = certificate();
        Instant found = NOW.minus(Duration.ofDays(30));
        revoked(certificate, found);
        for (int night = 29; night >= 0; night--) {
            revoked(certificate, NOW.minus(Duration.ofDays(night)));
        }

        assertThat(certificate.getRevocationCheckedAt()).isEqualTo(NOW);
        assertThat(certificate.getRevocationFoundAt()).isEqualTo(found);
    }

    @Test
    void leavingRevokedForgetsWhenItWasFound() {
        CachedCertificate certificate = certificate();
        revoked(certificate, NOW.minus(Duration.ofDays(3)));

        // A hold lifted: the authority no longer lists it.
        certificate.recordRevocation(RevocationStatus.GOOD, RevocationMethod.CRL, null, null, "CRL", NOW);

        assertThat(certificate.getRevocationStatus()).isEqualTo(RevocationStatus.GOOD);
        assertThat(certificate.getRevocationFoundAt()).isNull();
        assertThat(certificate.getRevokedAt()).isNull();
    }
}

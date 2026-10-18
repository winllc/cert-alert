package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * What {@code ENS_X509_REVOCATION} and {@code ENS_X509_REVOCATION_FAIL} mean when they are
 * left unset, and that every choice they had before is still there.
 */
class EnsX509RevocationDefaultsTest {

    /** Unset: a live check, OCSP then the CRL, that lets through what nobody can answer for. */
    @Test
    void unsetIsALiveSoftFailCheck() {
        EnsX509Properties x509 = new EnsX509Properties();

        assertThat(x509.revocationMode()).isEqualTo(EnsX509Properties.Revocation.OCSP_THEN_CRL);
        assertThat(x509.isRevocationDefaulted()).isTrue();
        assertThat(x509.isFailOpen()).isTrue();

        // An empty value, as an unset environment variable reaches the binder, is the same.
        x509.setRevocation("");
        x509.setRevocationFail("");
        assertThat(x509.revocationMode()).isEqualTo(EnsX509Properties.Revocation.OCSP_THEN_CRL);
        assertThat(x509.isFailOpen()).isTrue();
    }

    /** Fail-closed is still there, with or without choosing the mode. */
    @Test
    void failClosedIsStillAvailable() {
        EnsX509Properties x509 = new EnsX509Properties();
        x509.setRevocationFail("closed");
        assertThat(x509.revocationMode()).isEqualTo(EnsX509Properties.Revocation.OCSP_THEN_CRL);
        assertThat(x509.isFailOpen()).isFalse();
    }

    /**
     * A site that chose a mode itself and left the failure behaviour unset was fail-closed
     * before this default existed, and stays so: an upgrade does not loosen it.
     */
    @Test
    void aChosenModeKeepsFailClosedUnlessToldOtherwise() {
        for (String mode : new String[] {"ocsp", "crl", "ocsp-then-crl"}) {
            EnsX509Properties x509 = new EnsX509Properties();
            x509.setRevocation(mode);
            assertThat(x509.isRevocationDefaulted()).isFalse();
            assertThat(x509.isFailOpen()).as(mode).isFalse();

            x509.setRevocationFail("open");
            assertThat(x509.isFailOpen()).as(mode).isTrue();
        }
    }

    @Test
    void offIsStillAvailable() {
        EnsX509Properties x509 = new EnsX509Properties();
        x509.setRevocation("off");
        assertThat(x509.revocationMode()).isEqualTo(EnsX509Properties.Revocation.OFF);
    }
}

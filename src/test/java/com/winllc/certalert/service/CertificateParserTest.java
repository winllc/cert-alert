package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.support.TestCertificates;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class CertificateParserTest {

    private final CertificateParser parser = new CertificateParser();

    @Test
    void readsTheDetailsWorthCaching() {
        Instant notBefore = Instant.parse("2026-01-01T00:00:00Z");
        Instant notAfter = Instant.parse("2027-01-01T00:00:00Z");
        byte[] der = TestCertificates.der("host.example.gov", notBefore, notAfter);

        CachedCertificate cached = parser.parse(der, Instant.parse("2026-06-01T00:00:00Z"));

        assertThat(cached.getSubjectDn()).isEqualTo("CN=host.example.gov");
        assertThat(cached.getIssuerDn()).isEqualTo("CN=host.example.gov");
        assertThat(cached.getKeyAlgorithm()).isEqualTo("RSA");
        assertThat(cached.getKeySize()).isEqualTo(2048);
        assertThat(cached.getSubjectAlternativeNames()).isEqualTo("host.example.gov");
        assertThat(cached.getSerialNumber()).isNotBlank();
        // X.509 stores validity to the second.
        assertThat(cached.getNotBefore()).isEqualTo(notBefore.truncatedTo(ChronoUnit.SECONDS));
        assertThat(cached.getNotAfter()).isEqualTo(notAfter.truncatedTo(ChronoUnit.SECONDS));
        assertThat(cached.getCachedAt()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void fingerprintIsStableForTheSameBytesAndDiffersBetweenCertificates() {
        byte[] der = TestCertificates.expiringIn("stable.example.gov", Duration.ofDays(30));
        byte[] other = TestCertificates.expiringIn("other.example.gov", Duration.ofDays(30));
        Instant now = Instant.now();

        String first = parser.parse(der, now).getSha256Fingerprint();
        String again = parser.parse(der, now).getSha256Fingerprint();

        assertThat(first).hasSize(64).matches("[0-9a-f]{64}").isEqualTo(again);
        assertThat(parser.parse(other, now).getSha256Fingerprint()).isNotEqualTo(first);
    }

    @Test
    void rejectsBytesThatAreNotACertificate() {
        byte[] notACertificate = "this is not DER".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> parser.parse(notACertificate, Instant.now()))
                .isInstanceOf(CertificateParseException.class)
                .hasMessageContaining("readable X.509 certificate");
    }
}

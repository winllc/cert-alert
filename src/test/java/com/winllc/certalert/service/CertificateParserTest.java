package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.winllc.certalert.config.RiskProperties;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateRisk;
import com.winllc.certalert.support.TestCertificates;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CertificateParserTest {

    private final CertificateParser parser = new CertificateParser(new RiskProperties());

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
        assertThat(cached.getSignatureAlgorithm()).isEqualToIgnoringCase("SHA256withRSA");
        assertThat(cached.getHashAlgorithm()).isEqualTo("SHA-256");
        assertThat(cached.getSubjectAlternativeNames()).isEqualTo("host.example.gov");
        assertThat(cached.getSerialNumber()).isNotBlank();
        // X.509 stores validity to the second.
        assertThat(cached.getNotBefore()).isEqualTo(notBefore.truncatedTo(ChronoUnit.SECONDS));
        assertThat(cached.getNotAfter()).isEqualTo(notAfter.truncatedTo(ChronoUnit.SECONDS));
        assertThat(cached.getCachedAt()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
    }

    /**
     * The digest is cached on its own so a report can group and filter on it. The signature
     * algorithm carries it too, but only as half of a name, spelled however the provider
     * spells it.
     */
    @ParameterizedTest
    @CsvSource({
        "SHA256withRSA, SHA-256",
        "SHA1withRSA, SHA-1",
        "SHA384withRSA, SHA-384",
        "SHA512withRSA, SHA-512",
        "SHA3-256withRSA, SHA3-256",
        "SHA3-512withRSA, SHA3-512"
    })
    void cachesTheDigestApartFromTheSignatureAlgorithm(String signatureAlgorithm, String expected) {
        byte[] der = TestCertificates.der("digest.example.gov", signatureAlgorithm, null);

        CachedCertificate cached = parser.parse(der, Instant.now());

        assertThat(cached.getHashAlgorithm()).isEqualTo(expected);
        assertThat(cached.getKeyAlgorithm()).isEqualTo("RSA");
    }

    /**
     * The case pattern-matching the signature algorithm would get wrong: RSASSA-PSS names
     * no digest at all, because the digest it used is in the signature parameters.
     */
    @Test
    void readsTheDigestOutOfRsassaPssParameters() {
        byte[] der = TestCertificates.der("pss.example.gov", "SHA256withRSAandMGF1", null);

        CachedCertificate cached = parser.parse(der, Instant.now());

        // The name really does carry no digest, so this is the parameters being read.
        assertThat(cached.getSignatureAlgorithm()).isEqualTo("RSASSA-PSS");
        assertThat(cached.getHashAlgorithm()).isEqualTo("SHA-256");
    }

    @Test
    void cachesTheSizeAndKindOfAnEllipticCurveKey() {
        byte[] der = TestCertificates.der(
                "ec.example.gov", "SHA384withECDSA", TestCertificates.keyPair("EC", 384));

        CachedCertificate cached = parser.parse(der, Instant.now());

        // For an EC key the size is the field size, which is what names the curve: P-384.
        assertThat(cached.getKeySize()).isEqualTo(384);
        assertThat(cached.getKeyAlgorithm()).isEqualTo("EC");
        assertThat(cached.getHashAlgorithm()).isEqualTo("SHA-384");
    }

    @Test
    void cachesAnUndersizedKeyAsItFindsIt() {
        byte[] der = TestCertificates.der(
                "weak.example.gov", "SHA1withRSA", TestCertificates.keyPair("RSA", 1024));

        CachedCertificate cached = parser.parse(der, Instant.now());

        // Nothing here judges a certificate; it records what it is so a report can.
        assertThat(cached.getKeySize()).isEqualTo(1024);
        assertThat(cached.getHashAlgorithm()).isEqualTo("SHA-1");
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

    // ---------------------------------------------------------------------------------
    // What the names say
    // ---------------------------------------------------------------------------------

    /** The assessment is made from the certificate, and cached with it. */
    @Test
    void cachesWhatIsWorryingAboutTheNames() {
        byte[] der = TestCertificates.withNames("web01.example.gov", "web01.example.gov", "*.example.gov");

        CachedCertificate cached = parser.parse(der, Instant.now());

        assertThat(cached.getSubjectAltNameCount()).isEqualTo(2);
        assertThat(cached.getRisks()).containsExactly(CertificateRisk.WILDCARD);
        assertThat(cached.isRisky()).isTrue();
    }

    @Test
    void anOrdinaryCertificateCarriesNoFlags() {
        byte[] der = TestCertificates.withNames("web01.example.gov", "web01.example.gov", "www.example.gov");

        CachedCertificate cached = parser.parse(der, Instant.now());

        assertThat(cached.getSubjectAltNameCount()).isEqualTo(2);
        assertThat(cached.getRisks()).isEmpty();
        assertThat(cached.isRisky()).isFalse();
    }

    /**
     * The stored list is truncated when it is long; the count is not. A count taken from
     * the stored string would be short by exactly the names that make it worth flagging.
     */
    @Test
    void theCountIsHonestEvenWhenTheListIsTruncated() {
        String[] names = new String[120];
        for (int i = 0; i < names.length; i++) {
            names[i] = "host%03d.verylongdomainname.example.gov".formatted(i);
        }
        byte[] der = TestCertificates.withNames(names[0], names);

        CachedCertificate cached = parser.parse(der, Instant.now());

        assertThat(cached.getSubjectAlternativeNames()).endsWith("...");
        assertThat(cached.getSubjectAltNameCount()).isEqualTo(120);
        assertThat(cached.getRisks()).contains(CertificateRisk.MANY_NAMES);
    }
}

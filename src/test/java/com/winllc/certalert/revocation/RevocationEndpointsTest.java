package com.winllc.certalert.revocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.support.TestCa;
import com.winllc.certalert.support.TestCertificates;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Reading out of a certificate where to ask whether it has been revoked.
 *
 * <p>Both answers are in extensions the JDK does not hand over parsed, so they are read
 * from the encoding. Against certificates a CA really issued, because the thing that would
 * break this is an encoding that is correct and shaped differently from what was expected.
 */
class RevocationEndpointsTest {

    private static final Instant NOW = Instant.now();

    private final TestCa ca = new TestCa("Example Test CA");

    @Test
    void theDistributionPointAndTheResponderAreBothRead() {
        TestCa.Issued issued = ca.issue(
                "web01.example.gov",
                NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(364)),
                "http://crl.example.gov/ca.crl",
                "http://ocsp.example.gov");

        RevocationEndpoints endpoints = RevocationEndpoints.of(issued.certificate());

        assertThat(endpoints.crlUrls()).containsExactly("http://crl.example.gov/ca.crl");
        assertThat(endpoints.ocspUrl()).isEqualTo("http://ocsp.example.gov");
    }

    /**
     * The access method is what separates a responder from a place to fetch the CA's own
     * certificate, and both are URIs in the same extension. Taking the first URI would
     * quietly point every OCSP request at a file.
     */
    @Test
    void theCaIssuersUrlIsNotMistakenForAResponder() {
        TestCa.Issued issued = ca.issue(
                "web02.example.gov",
                NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(364)),
                null,
                "http://ocsp.example.gov/responder");

        RevocationEndpoints endpoints = RevocationEndpoints.of(issued.certificate());

        // The fixture puts a caIssuers entry first, as a real certificate does.
        assertThat(endpoints.ocspUrl()).isEqualTo("http://ocsp.example.gov/responder");
        assertThat(endpoints.crlUrls()).isEmpty();
    }

    @Test
    void aDistributionPointInsideADirectoryIsAUrlLikeAnyOther() {
        // Which is how a PKI with no route to the internet publishes its lists, and the
        // reason the stored form is space-separated: this one contains commas.
        TestCa.Issued issued = ca.issue(
                "web03.example.gov",
                NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(364)),
                "ldap://pki.example.gov/cn=CRL1,ou=pki,o=gov?certificateRevocationList;binary",
                null);

        assertThat(RevocationEndpoints.of(issued.certificate()).crlUrls())
                .containsExactly("ldap://pki.example.gov/cn=CRL1,ou=pki,o=gov?certificateRevocationList;binary");
    }

    @Test
    void aCertificateThatNamesNowhereSaysSoRatherThanFailing() {
        // Self-signed, no extensions of this kind at all - which is most of the fixtures
        // here, and every certificate in a directory that predates a PKI worth the name.
        RevocationEndpoints endpoints = RevocationEndpoints.of(
                TestCertificates.certificate("plain", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(1))));

        assertThat(endpoints.isEmpty()).isTrue();
        assertThat(endpoints.crlUrls()).isEmpty();
        assertThat(endpoints.ocspUrl()).isNull();
    }

    @Test
    void theIssuerIsIdentifiedByKeyRatherThanByName() {
        TestCa.Issued issued = ca.issue(
                "web04.example.gov",
                NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(364)),
                "http://crl.example.gov/ca.crl",
                null);

        // What the leaf points at and what the CA carries are the same value, which is what
        // lets an issuer be found among several of the same name.
        assertThat(RevocationEndpoints.authorityKeyId(issued.certificate()))
                .isNotNull()
                .isEqualTo(RevocationEndpoints.subjectKeyId(ca.certificate()));
    }
}

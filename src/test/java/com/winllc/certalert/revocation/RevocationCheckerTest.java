package com.winllc.certalert.revocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.config.RevocationProperties;
import com.winllc.certalert.config.RiskProperties;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.RevocationStatus;
import com.winllc.certalert.service.CertificateParser;
import com.winllc.certalert.support.TestCa;
import com.winllc.certalert.support.TestHttpServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the check does when the answer is not simply yes or no.
 *
 * <p>The interesting cases are all about not knowing: a distribution point that does not
 * answer, a list that cannot be shown to be the authority's own. None of them may come back
 * as {@link RevocationStatus#GOOD}, because the difference between "the authority does not
 * list this" and "nobody could be asked" is the whole value of the check.
 */
class RevocationCheckerTest {

    private static final Instant NOW = Instant.now();
    private static final String CRL_PATH = "/ca.crl";

    private final CertificateParser parser = new CertificateParser(new RiskProperties());
    private final TestCa ca = new TestCa("Example Verification CA");

    private TestHttpServer publisher;
    private RevocationProperties properties;

    @BeforeEach
    void publish() {
        publisher = new TestHttpServer();
        properties = new RevocationProperties();
    }

    @AfterEach
    void stop() {
        publisher.close();
    }

    @Test
    void aListFromAnUnknownIssuerIsUsedAndSaidToBeUnverified() {
        // No issuer certificates configured, which is the out-of-the-box state: the list
        // is worth more than no answer, and the result says what it could not establish.
        CachedCertificate certificate = issueAndRevoke();

        RevocationChecker.Outcome outcome = checker(null).check(certificate, NOW);

        assertThat(outcome.status()).isEqualTo(RevocationStatus.REVOKED);
        assertThat(outcome.detail()).contains("signature not verified");
    }

    @Test
    void andIsRefusedWhereThatIsNotGoodEnough() {
        CachedCertificate certificate = issueAndRevoke();
        properties.setAllowUnverifiedCrl(false);

        RevocationChecker.Outcome outcome = checker(null).check(certificate, NOW);

        // Not GOOD, and not REVOKED either: an unverifiable list answers nothing.
        assertThat(outcome.status()).isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(outcome.detail()).contains("could not be verified");
    }

    @Test
    void aListFromTheKnownIssuerIsVerified() throws IOException {
        CachedCertificate certificate = issueAndRevoke();

        RevocationChecker.Outcome outcome = checker(issuerDirectory()).check(certificate, NOW);

        assertThat(outcome.status()).isEqualTo(RevocationStatus.REVOKED);
        assertThat(outcome.detail()).doesNotContain("not verified");
        assertThat(outcome.reason()).isEqualTo("KEY_COMPROMISE");
    }

    @Test
    void aDistributionPointThatDoesNotAnswerLeavesTheQuestionOpen() {
        CachedCertificate certificate = parse(ca.issue(
                        "gone", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(364)),
                        publisher.url(CRL_PATH), null)
                .der());
        // Nothing published at that path: a 404, which is what a distribution point that
        // has moved looks like.

        RevocationChecker.Outcome outcome = checker(null).check(certificate, NOW);

        assertThat(outcome.status()).isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(outcome.detail()).contains("No distribution point answered");
    }

    @Test
    void theSecondDistributionPointIsTriedWhenTheFirstFails() {
        // A certificate naming two, the first of them dead. Issuers publish several for
        // exactly this reason, so trying only the first would throw the answer away.
        TestCa.Issued issued = ca.issue(
                "two-points",
                NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(364)),
                publisher.url("/missing.crl"),
                null);
        CachedCertificate certificate = parse(issued.der());
        certificate.describeRevocationEndpoints(
                java.util.List.of(publisher.url("/missing.crl"), publisher.url(CRL_PATH)),
                null,
                certificate.getAuthorityKeyId());
        publisher.put(CRL_PATH, ca.emptyCrl());

        RevocationChecker.Outcome outcome = checker(null).check(certificate, NOW);

        assertThat(outcome.status()).isEqualTo(RevocationStatus.GOOD);
        assertThat(outcome.detail()).contains(CRL_PATH);
    }

    // --- helpers ---------------------------------------------------------------------------

    private RevocationChecker checker(Path issuers) {
        properties.setIssuerDirectory(issuers == null ? null : issuers.toString());
        return new RevocationChecker(
                new CrlStore(properties), new IssuerCertificates(properties), properties);
    }

    private Path issuerDirectory() throws IOException {
        Path directory = Files.createTempDirectory("cert-alert-issuer");
        Files.writeString(directory.resolve("ca.pem"), ca.certificatePem());
        return directory;
    }

    /** A certificate the authority has revoked, with its list published where it says. */
    private CachedCertificate issueAndRevoke() {
        TestCa.Issued issued = ca.issue(
                "revoked",
                NOW.minus(Duration.ofDays(10)),
                NOW.plus(Duration.ofDays(355)),
                publisher.url(CRL_PATH),
                null);
        publisher.put(CRL_PATH, ca.crl(
                NOW.minus(Duration.ofHours(1)),
                NOW.plus(Duration.ofDays(1)),
                Map.of(issued.serial(), NOW.minus(Duration.ofDays(3)))));
        return parse(issued.der());
    }

    private CachedCertificate parse(byte[] der) {
        return parser.parse(der, NOW);
    }
}

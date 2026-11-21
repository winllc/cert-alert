package com.winllc.certalert.revocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.config.RevocationProperties;
import com.winllc.certalert.domain.RevocationStatus;
import com.winllc.certalert.security.EnsX509Properties;
import com.winllc.certalert.support.TestCa;
import com.winllc.certalert.support.TestHttpServer;
import com.winllc.certalert.support.TestOcspResponder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Revocation at sign-in ({@code ENS_X509_REVOCATION}): asked of a real responder and real
 * lists, with somebody's sign-in waiting on the answer.
 */
class SignInRevocationTest {

    private static final String CRL_PATH = "/ca.crl";

    private final TestCa ca = new TestCa("Example Sign-in CA");
    private final TestCa stranger = new TestCa("Some Other CA");
    private final MutableClock clock = new MutableClock(Instant.now());

    private TestOcspResponder responder;
    private TestHttpServer publisher;
    private EnsX509Properties settings;

    @TempDir
    Path crlDirectory;

    @BeforeEach
    void start() {
        responder = new TestOcspResponder(ca);
        publisher = new TestHttpServer();
        settings = new EnsX509Properties();
    }

    @AfterEach
    void stop() {
        responder.close();
        publisher.close();
    }

    @Test
    void offAsksNothing() {
        settings.setRevocation("off");
        TestCa.Issued issued = issue(null, responder.url());

        SignInRevocation.Answer answer = checker().check(issued.certificate(), ca.certificate());

        assertThat(answer.status()).isEqualTo(RevocationStatus.NOT_CHECKED);
        assertThat(responder.requestCount()).isZero();
    }

    /** Left unset, the check is on: the responder the certificate names is asked. */
    @Test
    void unsetAsksTheResponder() {
        TestCa.Issued good = issue(null, responder.url());
        TestCa.Issued revoked = issue(null, responder.url());
        responder.revoke(revoked.serial(), clock.instant().minus(Duration.ofDays(1)));

        assertThat(checker().isEnabled()).isTrue();
        assertThat(checker().check(good.certificate(), ca.certificate()).status()).isEqualTo(RevocationStatus.GOOD);
        assertThat(checker().check(revoked.certificate(), ca.certificate()).status())
                .isEqualTo(RevocationStatus.REVOKED);
        assertThat(responder.requestCount()).isEqualTo(2);
    }

    @Test
    void theResponderTheCertificateNamesIsAsked() {
        settings.setRevocation("ocsp");
        TestCa.Issued good = issue(null, responder.url());
        TestCa.Issued revoked = issue(null, responder.url());
        responder.revoke(revoked.serial(), clock.instant().minus(Duration.ofDays(1)));
        SignInRevocation checker = checker();

        assertThat(checker.check(good.certificate(), ca.certificate()).status()).isEqualTo(RevocationStatus.GOOD);
        assertThat(checker.check(revoked.certificate(), ca.certificate()).status())
                .isEqualTo(RevocationStatus.REVOKED);
    }

    @Test
    void aConfiguredResponderIsAskedInsteadOfTheCertificatesOwn() {
        settings.setRevocation("ocsp");
        settings.setOcspResponderUrl(responder.url());
        // Names a responder nobody is listening at.
        TestCa.Issued issued = issue(null, "http://localhost:1/ocsp");

        assertThat(checker().check(issued.certificate(), ca.certificate()).status()).isEqualTo(RevocationStatus.GOOD);
        assertThat(responder.requestCount()).isEqualTo(1);
    }

    @Test
    void anAnswerIsReusedForTheCachePeriod() {
        settings.setRevocation("ocsp");
        settings.setRevocationCache(Duration.ofMinutes(10));
        TestCa.Issued issued = issue(null, responder.url());
        SignInRevocation checker = checker();

        checker.check(issued.certificate(), ca.certificate());
        checker.check(issued.certificate(), ca.certificate());
        assertThat(responder.requestCount()).isEqualTo(1);

        clock.advance(Duration.ofMinutes(11));
        checker.check(issued.certificate(), ca.certificate());
        assertThat(responder.requestCount()).isEqualTo(2);
    }

    @Test
    void noAnswerIsUnknownNeverGood() {
        settings.setRevocation("ocsp");
        TestCa.Issued unreachable = issue(null, "http://localhost:1/ocsp");
        TestCa.Issued namesNone = issue(null, null);
        SignInRevocation checker = checker();

        assertThat(checker.check(unreachable.certificate(), ca.certificate()).status())
                .isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(checker.check(namesNone.certificate(), ca.certificate()))
                .satisfies(answer -> {
                    assertThat(answer.status()).isEqualTo(RevocationStatus.UNKNOWN);
                    assertThat(answer.detail()).contains("names no OCSP responder");
                });
        // And with no issuer certificate there is nothing to ask with.
        assertThat(checker.check(issue(null, responder.url()).certificate(), null).status())
                .isEqualTo(RevocationStatus.UNKNOWN);
    }

    @Test
    void theListIsReadWhereTheCertificateSays() {
        settings.setRevocation("crl");
        TestCa.Issued good = issue(publisher.url(CRL_PATH), null);
        TestCa.Issued revoked = issue(publisher.url(CRL_PATH), null);
        publisher.put(CRL_PATH, ca.crl(clock.instant().minus(Duration.ofHours(1)), clock.instant().plus(Duration.ofDays(1)),
                Map.of(revoked.serial(), clock.instant().minus(Duration.ofHours(2)))));
        SignInRevocation checker = checker();

        assertThat(checker.check(good.certificate(), ca.certificate()).status()).isEqualTo(RevocationStatus.GOOD);
        assertThat(checker.check(revoked.certificate(), ca.certificate()).status()).isEqualTo(RevocationStatus.REVOKED);
    }

    @Test
    void aListNotSignedByTheIssuerOrPastItsNextUpdateAnswersNothing() {
        settings.setRevocation("crl");
        TestCa.Issued forged = issue(publisher.url("/forged.crl"), null);
        TestCa.Issued stale = issue(publisher.url("/stale.crl"), null);
        publisher.put("/forged.crl", stranger.emptyCrl());
        publisher.put("/stale.crl", ca.crl(clock.instant().minus(Duration.ofDays(9)),
                clock.instant().minus(Duration.ofDays(2)), Map.of()));
        SignInRevocation checker = checker();

        assertThat(checker.check(forged.certificate(), ca.certificate()).status()).isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(checker.check(stale.certificate(), ca.certificate()))
                .satisfies(answer -> {
                    assertThat(answer.status()).isEqualTo(RevocationStatus.UNKNOWN);
                    assertThat(answer.detail()).contains("out of date");
                });
    }

    @Test
    void theListIsReadWhenTheResponderGivesNoAnswer() {
        settings.setRevocation("ocsp-then-crl");
        TestCa.Issued issued = issue(publisher.url(CRL_PATH), "http://localhost:1/ocsp");
        publisher.put(CRL_PATH, ca.crl(clock.instant().minus(Duration.ofHours(1)), clock.instant().plus(Duration.ofDays(1)),
                Map.of(issued.serial(), clock.instant().minus(Duration.ofHours(2)))));

        assertThat(checker().check(issued.certificate(), ca.certificate()).status()).isEqualTo(RevocationStatus.REVOKED);
    }

    @Test
    void anAirGappedDirectoryOfListsIsReadAndNothingIsFetched() throws Exception {
        settings.setRevocation("crl");
        settings.setCrlDir(crlDirectory.toString());
        TestCa.Issued issued = issue(publisher.url(CRL_PATH), null);
        Files.write(crlDirectory.resolve("example.crl"), ca.emptyCrl());
        SignInRevocation checker = checker();

        assertThat(checker.check(issued.certificate(), ca.certificate()).status()).isEqualTo(RevocationStatus.GOOD);

        // A new list copied in is read once it is seen - and the cached answer has run out.
        Files.write(crlDirectory.resolve("example.crl"), ca.crl(clock.instant().minus(Duration.ofMinutes(1)),
                clock.instant().plus(Duration.ofDays(1)), Map.of(issued.serial(), clock.instant())));
        Files.setLastModifiedTime(crlDirectory.resolve("example.crl"),
                java.nio.file.attribute.FileTime.from(Instant.now().plusSeconds(60)));
        clock.advance(Duration.ofMinutes(11));
        assertThat(checker.check(issued.certificate(), ca.certificate()).status()).isEqualTo(RevocationStatus.REVOKED);
        assertThat(publisher.requestCount()).as("nothing fetched over the network").isZero();
    }

    /** Finding the issuer costs a signature check; an answer that is reused does not need one. */
    @Test
    void theIssuerIsLookedForOnlyWhenThereIsNoAnswerToReuse() {
        settings.setRevocation("ocsp");
        TestCa.Issued issued = issue(null, responder.url());
        SignInRevocation checker = checker();
        java.util.concurrent.atomic.AtomicInteger lookups = new java.util.concurrent.atomic.AtomicInteger();

        SignInRevocation.Answer first = checker.checkFindingIssuer(issued.certificate(), () -> {
            lookups.incrementAndGet();
            return ca.certificate();
        });
        SignInRevocation.Answer second = checker.checkFindingIssuer(issued.certificate(), () -> {
            lookups.incrementAndGet();
            return ca.certificate();
        });

        assertThat(lookups).hasValue(1);
        assertThat(first.status()).isEqualTo(RevocationStatus.GOOD);
        // What is said about an answer is said once: the reused one says it is reused.
        assertThat(first.fresh()).isTrue();
        assertThat(second.fresh()).isFalse();
    }

    /** A page's parallel requests for one certificate ask the responder once between them. */
    @Test
    void requestsForTheSameCertificateAtOnceAskOnce() throws Exception {
        settings.setRevocation("ocsp");
        responder.takesThisLong(500);
        TestCa.Issued issued = issue(null, responder.url());
        SignInRevocation checker = checker();

        java.util.concurrent.ExecutorService requests = java.util.concurrent.Executors.newFixedThreadPool(6);
        try {
            List<java.util.concurrent.Future<SignInRevocation.Answer>> answers = new java.util.ArrayList<>();
            for (int i = 0; i < 6; i++) {
                answers.add(requests.submit(() -> checker.check(issued.certificate(), ca.certificate())));
            }
            for (java.util.concurrent.Future<SignInRevocation.Answer> answer : answers) {
                assertThat(answer.get(30, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo(RevocationStatus.GOOD);
            }
        } finally {
            requests.shutdownNow();
        }
        assertThat(responder.requestCount()).isEqualTo(1);
    }

    /** No answer is reused for longer each time it is still no answer, up to a ceiling. */
    @Test
    void noAnswerIsReusedForLongerWhileNobodyAnswers() {
        assertThat(SignInRevocation.unknownHold(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(SignInRevocation.unknownHold(2)).isEqualTo(Duration.ofSeconds(60));
        assertThat(SignInRevocation.unknownHold(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(SignInRevocation.unknownHold(50)).isEqualTo(Duration.ofMinutes(2));

        settings.setRevocation("ocsp");
        TestCa.Issued unreachable = issue(null, "http://localhost:1/ocsp");
        SignInRevocation checker = checker();

        assertThat(checker.check(unreachable.certificate(), ca.certificate()).fresh()).isTrue();
        clock.advance(Duration.ofSeconds(31));
        assertThat(checker.check(unreachable.certificate(), ca.certificate()).fresh()).as("asked again").isTrue();
        clock.advance(Duration.ofSeconds(31));
        assertThat(checker.check(unreachable.certificate(), ca.certificate()).fresh()).as("now held a minute").isFalse();
        clock.advance(Duration.ofSeconds(30));
        assertThat(checker.check(unreachable.certificate(), ca.certificate()).fresh()).isTrue();
    }

    private SignInRevocation checker() {
        RevocationProperties revocation = new RevocationProperties();
        FetchPolicy policy = FetchPolicy.of(List.of("localhost", "127.0.0.1"));
        return new SignInRevocation(settings, new CrlStore(revocation, policy, clock), policy, revocation, clock);
    }

    private TestCa.Issued issue(String crlUrl, String ocspUrl) {
        return ca.issue("person", clock.instant().minus(Duration.ofDays(10)), clock.instant().plus(Duration.ofDays(300)),
                crlUrl, ocspUrl);
    }

    /** A clock a test moves on. */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

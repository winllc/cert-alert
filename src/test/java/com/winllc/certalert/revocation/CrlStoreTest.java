package com.winllc.certalert.revocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.config.RevocationProperties;
import com.winllc.certalert.support.TestCa;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * How long the store holds on to what a distribution point said, and how long anybody waits
 * for it - with a stand-in for the network and a clock the test moves.
 */
class CrlStoreTest {

    private static final String URL = "http://pki.example.gov/ca.crl";

    private final TestCa ca = new TestCa("Example Store CA");
    private final SignInRevocationTest.MutableClock clock = new SignInRevocationTest.MutableClock(Instant.now());
    private final RevocationProperties properties = new RevocationProperties();

    /** The CA-C-1 case: a list whose next update has passed is fetched again, not given up on. */
    @Test
    void aListPastItsTimeIsFetchedAgain() {
        Instant start = clock.instant();
        FakeSource source = new FakeSource(Duration.ofSeconds(30), () -> ca.crl(
                clock.instant().minus(Duration.ofMinutes(5)), clock.instant().plus(Duration.ofHours(1)), Map.of()));
        CrlStore store = new CrlStore(properties, source, clock);

        assertThat(store.fetch(List.of(URL), null)).isPresent();
        assertThat(store.fetch(List.of(URL), null)).isPresent();
        assertThat(source.calls()).as("held, not fetched per certificate").isEqualTo(1);

        clock.advance(Duration.ofHours(2));
        Optional<CrlStore.Fetched> again = store.fetch(List.of(URL), null);

        assertThat(again).isPresent();
        assertThat(source.calls()).isEqualTo(2);
        assertThat(again.get().crl().getThisUpdate().toInstant()).isAfter(start);
    }

    /** The CA-C-3 case: a point that did not answer is left alone for a while, not asked per certificate. */
    @Test
    void aPointThatDidNotAnswerIsNotAskedAgainStraightAway() {
        FakeSource source = new FakeSource(Duration.ofSeconds(30), () -> {
            throw new IllegalStateException("connect timed out");
        });
        CrlStore store = new CrlStore(properties, source, clock);

        assertThat(store.fetch(List.of(URL), null)).isEmpty();
        assertThat(store.fetch(List.of(URL), null)).isEmpty();
        assertThat(source.calls()).isEqualTo(1);

        clock.advance(CrlStore.RETRY_AFTER.plusSeconds(1));
        assertThat(store.fetch(List.of(URL), null)).isEmpty();
        assertThat(source.calls()).as("asked again once the retry interval is up").isEqualTo(2);
    }

    /**
     * The CA-C-4 case: a point serving an out-of-date list is what it is serving. The list is
     * handed back (the caller decides what it answers) and not downloaded once per certificate.
     */
    @Test
    void aListAlreadyOutOfDateIsHandedBackAndHeldBriefly() {
        FakeSource source = new FakeSource(Duration.ofSeconds(30), () -> ca.crl(
                clock.instant().minus(Duration.ofDays(8)), clock.instant().minus(Duration.ofDays(1)), Map.of()));
        CrlStore store = new CrlStore(properties, source, clock);

        assertThat(store.fetch(List.of(URL), null)).isPresent();
        assertThat(store.fetch(List.of(URL), null)).isPresent();
        assertThat(source.calls()).isEqualTo(1);

        clock.advance(CrlStore.RETRY_AFTER.plusSeconds(1));
        assertThat(store.fetch(List.of(URL), null)).isPresent();
        assertThat(source.calls()).isEqualTo(2);
    }

    /**
     * The CA-C-9 case: somebody signing in while the nightly job is downloading the same list
     * waits no longer than their own download would have taken.
     */
    @Test
    void aCallerWaitsOnSomebodyElsesDownloadOnlyAsLongAsItsOwnWouldTake() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        FakeSource slow = new FakeSource(Duration.ofMinutes(5), () -> {
            started.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ca.emptyCrl();
        });
        FakeSource impatient = new FakeSource(Duration.ofMillis(200), ca::emptyCrl);
        CrlStore store = new CrlStore(properties, slow, clock);

        CompletableFuture<Optional<CrlStore.Fetched>> nightly =
                CompletableFuture.supplyAsync(() -> store.fetch(List.of(URL), null));
        assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

        long before = System.nanoTime();
        Optional<CrlStore.Fetched> signIn = store.fetch(List.of(URL), null, impatient);
        Duration waited = Duration.ofNanos(System.nanoTime() - before);

        assertThat(signIn).as("no answer in time is no answer").isEmpty();
        assertThat(waited).isLessThan(Duration.ofSeconds(5));
        assertThat(impatient.calls()).as("and no second download of the same list").isZero();

        release.countDown();
        assertThat(nightly.get(10, TimeUnit.SECONDS)).isPresent();
        // Once it is in, everybody has it.
        assertThat(store.fetch(List.of(URL), null, impatient)).isPresent();
        assertThat(slow.calls()).isEqualTo(1);
    }

    @Test
    void theNextPointIsTriedWhenTheFirstIsLeftAlone() {
        FakeSource source = new FakeSource(Duration.ofSeconds(30), null) {
            @Override
            public byte[] get(String url, String accept) throws IOException {
                super.get(url, accept);
                if (url.equals(URL)) {
                    throw new IOException("HTTP 404");
                }
                return ca.emptyCrl();
            }
        };
        CrlStore store = new CrlStore(properties, source, clock);
        List<String> points = List.of(URL, "http://pki2.example.gov/ca.crl");

        assertThat(store.fetch(points, null)).get().extracting(CrlStore.Fetched::url)
                .isEqualTo("http://pki2.example.gov/ca.crl");
        assertThat(store.fetch(points, null)).isPresent();
        assertThat(source.calls()).isEqualTo(2);
    }

    /** Stands in for the network: counts what it is asked, answers what it is told to. */
    private static class FakeSource implements CrlStore.Source {

        private final Duration patience;
        private final Supplier<byte[]> answer;
        private final AtomicInteger calls = new AtomicInteger();

        FakeSource(Duration patience, Supplier<byte[]> answer) {
            this.patience = patience;
            this.answer = answer;
        }

        int calls() {
            return calls.get();
        }

        @Override
        public byte[] get(String url, String accept) throws IOException {
            calls.incrementAndGet();
            return answer == null ? null : answer.get();
        }

        @Override
        public Duration patience() {
            return patience;
        }
    }
}

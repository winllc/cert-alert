package com.winllc.certalert.security.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;

/**
 * The identity provider's metadata is read again after its lifetime - so a rolled signing
 * certificate is followed without a restart - keeping the last good copy when a read fails.
 */
class RefreshingRegistrationTest {

    private static final Instant START = Instant.parse("2026-11-21T12:00:00Z");

    private final Instant[] now = {START};

    private final Clock clock = new Clock() {
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
            return now[0];
        }
    };

    /** Each read takes the next answer: a registration, or an exception to throw. */
    private final Deque<Object> answers = new ArrayDeque<>();
    private int reads;

    private final Supplier<RelyingPartyRegistration> read = () -> {
        reads++;
        Object answer = answers.removeFirst();
        if (answer instanceof RuntimeException e) {
            throw e;
        }
        return (RelyingPartyRegistration) answer;
    };

    private RefreshingRegistration registration(Duration lifetime) {
        return new RefreshingRegistration(read, lifetime, "https://idp.example/metadata", clock);
    }

    @Test
    void theMetadataIsReadAgainOnceItsLifetimeHasPassed() {
        RelyingPartyRegistration first = mock(RelyingPartyRegistration.class);
        RelyingPartyRegistration rolled = mock(RelyingPartyRegistration.class);
        answers.add(first);
        answers.add(rolled);
        RefreshingRegistration registration = registration(Duration.ofHours(1));

        assertThat(registration.get()).isSameAs(first);
        now[0] = START.plus(Duration.ofMinutes(59));
        assertThat(registration.get()).isSameAs(first);
        assertThat(reads).isEqualTo(1);

        now[0] = START.plus(Duration.ofHours(1));
        assertThat(registration.get()).isSameAs(rolled);
        assertThat(reads).isEqualTo(2);
    }

    @Test
    void aFailedReadKeepsTheLastGoodCopyAndIsTriedAgainAMinuteLater() {
        RelyingPartyRegistration first = mock(RelyingPartyRegistration.class);
        RelyingPartyRegistration later = mock(RelyingPartyRegistration.class);
        answers.add(first);
        answers.add(new IllegalStateException("connection refused"));
        answers.add(later);
        RefreshingRegistration registration = registration(Duration.ofHours(1));
        registration.get();

        now[0] = START.plus(Duration.ofHours(2));
        assertThat(registration.get()).isSameAs(first);
        now[0] = now[0].plusSeconds(30);
        assertThat(registration.get()).isSameAs(first);
        assertThat(reads).isEqualTo(2);

        now[0] = now[0].plus(RefreshingRegistration.RETRY);
        assertThat(registration.get()).isSameAs(later);
        assertThat(reads).isEqualTo(3);
    }

    @Test
    void withNothingReadYetAFailureIsThrownAndTriedAgainNextTime() {
        RelyingPartyRegistration first = mock(RelyingPartyRegistration.class);
        answers.add(new IllegalStateException("connection refused"));
        answers.add(first);
        RefreshingRegistration registration = registration(Duration.ofHours(1));

        assertThatThrownBy(registration::get).isInstanceOf(IllegalStateException.class);
        assertThat(registration.get()).isSameAs(first);
    }

    @Test
    void aSignatureFailureBringsTheNextReadForwardAtMostEveryFiveMinutes() {
        RelyingPartyRegistration first = mock(RelyingPartyRegistration.class);
        RelyingPartyRegistration rolled = mock(RelyingPartyRegistration.class);
        answers.add(first);
        answers.add(rolled);
        RefreshingRegistration registration = registration(Duration.ofHours(1));
        registration.get();

        now[0] = START.plusSeconds(10);
        assertThat(registration.readAgainAfterSignatureFailure()).isSameAs(rolled);
        assertThat(registration.get()).isSameAs(rolled);

        now[0] = START.plus(Duration.ofMinutes(2));
        assertThat(registration.readAgainAfterSignatureFailure()).isNull();
        assertThat(reads).isEqualTo(2);
    }

    @Test
    void zeroReadsItOnce() {
        RelyingPartyRegistration first = mock(RelyingPartyRegistration.class);
        answers.add(first);
        RefreshingRegistration registration = registration(Duration.ZERO);
        registration.get();
        now[0] = START.plus(Duration.ofDays(365));
        assertThat(registration.get()).isSameAs(first);
        assertThat(reads).isEqualTo(1);
    }
}

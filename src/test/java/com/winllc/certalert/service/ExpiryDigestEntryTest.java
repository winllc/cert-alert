package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.OwnerType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a one-credential round-up keeps on the page. It is compared with yesterday's to decide
 * whether anything is new, so it must not change just because a day has passed.
 */
class ExpiryDigestEntryTest {

    private static final Instant TODAY = Instant.parse("2026-06-01T07:00:00Z");
    private static final Instant NOT_AFTER = TODAY.plus(Duration.ofDays(12));

    @Test
    void theDatedSummaryReadsTheSameTomorrow() {
        ExpiryDigest.Entry today = entry(TODAY);
        ExpiryDigest.Entry tomorrow = entry(TODAY.plus(Duration.ofDays(1)));

        assertThat(today.getSummary()).isNotEqualTo(tomorrow.getSummary());
        assertThat(today.getDatedSummary())
                .isEqualTo(tomorrow.getDatedSummary())
                .isEqualTo("Olive Owner - CN=olive - expires on 2026-06-13");
    }

    @Test
    void itSaysSoOnceItHasExpired() {
        assertThat(entry(NOT_AFTER.plus(Duration.ofDays(2))).getDatedSummary())
                .isEqualTo("Olive Owner - CN=olive - expired on 2026-06-13");
    }

    private static ExpiryDigest.Entry entry(Instant now) {
        CachedCertificate certificate = new CachedCertificate("ab", "01", "CN=olive", "CN=ca",
                NOT_AFTER.minus(Duration.ofDays(365)), NOT_AFTER, "SHA256withRSA", "SHA-256", "RSA", 2048, null, TODAY);
        AuditEvent.SubjectRef subject = new AuditEvent.SubjectRef(OwnerType.USER, 1L, "uid=olive", "Olive Owner");
        Credential credential = Credential.group(List.of(certificate), Duration.ofDays(1)).getFirst();
        return ExpiryDigest.Entry.of(credential, subject, OwnerType.USER, now);
    }
}

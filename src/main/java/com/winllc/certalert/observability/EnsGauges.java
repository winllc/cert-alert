package com.winllc.certalert.observability;

import com.enssolutions.license.LicenseCheck;
import com.enssolutions.license.LicenseState;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.RevocationStatus;
import com.winllc.certalert.license.Licensing;
import com.winllc.certalert.repository.CachedCertificateRepository;
import com.winllc.certalert.service.MailOutbox;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The ENS baseline's gauges: readings of how things stand, taken when Prometheus asks.
 *
 * <ul>
 *   <li>{@code ens_license_mode{mode}} - 1 for the license's current mode (FULL, READ_ONLY,
 *       UNLICENSED, as the audit trail names them), 0 for the others;
 *   <li>{@code ens_license_days_remaining} - days until the license expires (or its grace
 *       ends); NaN for a perpetual one;
 *   <li>{@code ens_certalert_certificates{status}} - cached certificates in each state;
 *   <li>{@code ens_certalert_revocation_unknown} - cached certificates whose authority was asked
 *       and gave no answer;
 *   <li>{@code ens_certalert_mail_outbox{state}} - email waiting to be sent, and given up on.
 * </ul>
 *
 * <p>The counts are grouped counts done by the database, reused for a minute so a scrape
 * every few seconds does not become a query every few seconds against a large cache.
 */
@Component
public class EnsGauges implements MeterBinder {

    private static final Duration REUSE = Duration.ofMinutes(1);

    private final Licensing licensing;
    private final CachedCertificateRepository certificates;
    private final MailOutbox outbox;
    private final Clock clock;

    private final Reading<Map<CertificateStatus, Long>> byStatus;
    private final Reading<Long> revocationUnknown;
    private final Reading<long[]> mail;

    public EnsGauges(
            Licensing licensing, CachedCertificateRepository certificates, MailOutbox outbox, Clock clock) {
        this.licensing = licensing;
        this.certificates = certificates;
        this.outbox = outbox;
        this.clock = clock;
        this.byStatus = new Reading<>(() -> {
            Map<CertificateStatus, Long> counts = new EnumMap<>(CertificateStatus.class);
            for (CertificateStatus status : CertificateStatus.values()) {
                counts.put(status, this.certificates.countByStatus(status));
            }
            return counts;
        });
        this.revocationUnknown = new Reading<>(() -> this.certificates.countByRevocationStatus(RevocationStatus.UNKNOWN));
        this.mail = new Reading<>(() -> new long[] {this.outbox.pending(), this.outbox.failed()});
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (LicenseState.Mode mode : LicenseState.Mode.values()) {
            Gauge.builder("ens.license.mode", this, gauges -> gauges.licenseMode() == mode ? 1 : 0)
                    .description("1 for the license's current mode")
                    .tag("mode", mode.name())
                    .register(registry);
        }
        Gauge.builder("ens.license.days.remaining", this, EnsGauges::daysRemaining)
                .description("Days until the license expires, or its grace period ends; NaN when it does not")
                .register(registry);
        for (CertificateStatus status : CertificateStatus.values()) {
            Gauge.builder("ens.certalert.certificates", this, gauges -> gauges.byStatus.get().getOrDefault(status, 0L))
                    .description("Cached certificates in each state")
                    .tag("status", status.name().toLowerCase(Locale.ROOT))
                    .register(registry);
        }
        Gauge.builder("ens.certalert.revocation.unknown", this, gauges -> gauges.revocationUnknown.get())
                .description("Cached certificates whose authority gave no revocation answer")
                .register(registry);
        Gauge.builder("ens.certalert.mail.outbox", this, gauges -> gauges.mail.get()[0])
                .description("Email in the outbox")
                .tag("state", "pending")
                .register(registry);
        Gauge.builder("ens.certalert.mail.outbox", this, gauges -> gauges.mail.get()[1])
                .description("Email in the outbox")
                .tag("state", "failed")
                .register(registry);
    }

    private LicenseState.Mode licenseMode() {
        // The manager's own reading: asking through Licensing would record a mode change from
        // the scrape thread, which is the audit scheduler's job.
        return licensing.manager().current().mode();
    }

    private double daysRemaining() {
        LicenseCheck check = licensing.manager().current();
        Optional<Long> days = check.daysLeft();
        return days.map(Long::doubleValue).orElse(Double.NaN);
    }

    /** A value worked out at most once a minute. */
    private final class Reading<T> {

        private final Supplier<T> source;
        private volatile T value;
        private volatile Instant takenAt = Instant.EPOCH;

        Reading(Supplier<T> source) {
            this.source = source;
        }

        T get() {
            Instant now = Instant.now(clock);
            if (value == null || !now.isBefore(takenAt.plus(REUSE))) {
                value = source.get();
                takenAt = now;
            }
            return value;
        }
    }
}

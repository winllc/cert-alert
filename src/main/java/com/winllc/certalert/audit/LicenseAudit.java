package com.winllc.certalert.audit;

import com.enssolutions.license.EnsLicense;
import com.enssolutions.license.LicenseCheck;
import com.winllc.certalert.domain.AuditEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The license's audit events.
 *
 * <ul>
 *   <li>{@code license.installed} and {@code license.rejected}, from the license screen;
 *   <li>{@code license.mode-changed} (FULL, READ_ONLY, UNLICENSED) whenever a license check
 *       comes out in a different mode from the last one recorded - at startup against the
 *       trail itself, then on every request and every minute;
 *   <li>{@code license.limit-exceeded} when the size limit stops a sweep adding somebody - at
 *       most once an hour, with how many were refused;
 *   <li>{@code job.skipped}, once each time a job stands down for the license.
 * </ul>
 */
@Component
public class LicenseAudit {

    private static final Duration LIMIT_WINDOW = Duration.ofHours(1);

    @PersistenceContext
    private EntityManager entityManager;

    private final AuditLog audit;
    private final Clock clock;

    /** The mode last recorded; null until startup has read it from the trail. */
    private final AtomicReference<String> lastMode = new AtomicReference<>();
    private volatile boolean started;

    private Instant limitWindowStart;
    private int limitRefusals;

    public LicenseAudit(AuditLog audit, Clock clock) {
        this.audit = audit;
        this.clock = clock;
    }

    /** At startup: the last mode the trail recorded, then a check against the current one. */
    @Transactional(readOnly = true)
    public String recordedMode() {
        List<AuditEvent> last = entityManager
                .createQuery("select e from AuditEvent e where e.eventAction = 'license.mode-changed' "
                        + "and e.seq is not null order by e.seq desc", AuditEvent.class)
                .setMaxResults(1)
                .getResultList();
        if (last.isEmpty()) {
            return null;
        }
        String line = last.getFirst().getLine();
        int at = line == null ? -1 : line.indexOf("\"mode.new\":\"");
        if (at < 0) {
            return null;
        }
        int start = at + "\"mode.new\":\"".length();
        return line.substring(start, line.indexOf('"', start));
    }

    public void start(LicenseCheck check) {
        lastMode.set(recordedMode());
        started = true;
        observe(check);
    }

    /** Records a mode change if this check's mode is not the last one recorded. */
    public void observe(LicenseCheck check) {
        if (!started || check == null) {
            return;
        }
        String mode = check.mode().name();
        String previous = lastMode.get();
        if (mode.equals(previous) || !lastMode.compareAndSet(previous, mode)) {
            return;
        }
        AuditRecord event = AuditRecord.of(AuditRecord.Category.LICENSE, "license.mode-changed")
                .actor(AuditRecord.Actor.system("license"))
                .severity("FULL".equals(mode) ? AuditRecord.Severity.NOTICE : AuditRecord.Severity.WARNING)
                .change("mode", previous, mode)
                .detail("state", check.state().name())
                .detail("message", check.message());
        describe(event, check.license().orElse(null));
        if (!audit.recordQuietly(event)) {
            // Try again next time rather than lose the change.
            lastMode.compareAndSet(mode, previous);
        }
    }

    public void installed(LicenseCheck check) {
        AuditRecord event = AuditRecord.of(AuditRecord.Category.LICENSE, "license.installed")
                .severity(AuditRecord.Severity.NOTICE)
                .detail("state", check.state().name())
                .detail("mode", check.mode().name())
                .detail("message", check.message());
        EnsLicense license = check.license().orElse(null);
        describe(event, license);
        if (license != null) {
            event.target("license", license.id(), license.licensee());
        }
        audit.record(event);
        observe(check);
    }

    public void rejected(String reason, LicenseCheck check) {
        AuditRecord event = AuditRecord.of(AuditRecord.Category.LICENSE, "license.rejected")
                .outcome(AuditRecord.Outcome.FAILURE)
                .reason(reason);
        if (check != null) {
            event.detail("state", check.state().name());
            describe(event, check.license().orElse(null));
        }
        audit.record(event);
    }

    /** The size limit refused somebody. Collapsed to one event an hour, with the count. */
    public void limitExceeded(long current, Long limit, String unit) {
        Instant now = Instant.now(clock);
        int refused;
        synchronized (this) {
            if (limitWindowStart != null && Duration.between(limitWindowStart, now).compareTo(LIMIT_WINDOW) < 0) {
                limitRefusals++;
                return;
            }
            refused = limitRefusals + 1;
            limitWindowStart = now;
            limitRefusals = 0;
        }
        audit.recordQuietly(AuditRecord.of(AuditRecord.Category.LICENSE, "license.limit-exceeded")
                .actor(AuditRecord.Actor.system("license"))
                .outcome(AuditRecord.Outcome.DENIED)
                .reason("the license's size limit is reached")
                .detail("current", current)
                .detail("limit", limit)
                .detail("unit", unit)
                .detail("refused", refused));
    }

    /** A scheduled job standing down because of the license. Called once per stand-down. */
    public void jobSkipped(String job, String feature, LicenseCheck check, String reason) {
        audit.recordQuietly(AuditRecord.of(AuditRecord.Category.SYSTEM, "job.skipped")
                .actor(AuditRecord.Actor.system("scheduler"))
                .severity(AuditRecord.Severity.NOTICE)
                .reason(reason)
                .detail("job", job)
                .detail("feature", feature)
                .detail("mode", check.mode().name())
                .detail("state", check.state().name()));
    }

    private static void describe(AuditRecord event, EnsLicense license) {
        if (license == null) {
            return;
        }
        event.detail("licenseId", license.id())
                .detail("revision", license.revision())
                .detail("edition", license.edition())
                .detail("licensee", license.licensee())
                .detail("expiresAt", license.expiresAt().map(AuditFormat::time).orElse(null));
    }
}

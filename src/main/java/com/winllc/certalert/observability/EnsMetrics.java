package com.winllc.certalert.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The ENS baseline's business metrics that count things as they happen, named as every ENS
 * product names them (rendered by Prometheus with an {@code _total} suffix):
 *
 * <ul>
 *   <li>{@code ens_audit_events_total{category,outcome}} - events written to the audit trail;
 *   <li>{@code ens_audit_forward_failures_total{output}} - failed attempts to forward them;
 *   <li>{@code ens_auth_attempts_total{method,outcome}} - sign-ins and refusals, {@code x509}
 *       or {@code ldap}; a certificate counts once per session, like its audit record;
 *   <li>{@code ens_certalert_digest_truncated_total} - round-ups that stopped at the scan limit;
 *   <li>{@code ens_certalert_job_last_success_seconds{job}} - when each scheduled or manual job
 *       last finished without failing, as Unix time; absent until it has since startup.
 * </ul>
 *
 * <p>The ones that are a reading of the current state are {@link EnsGauges}.
 */
@Component
public class EnsMetrics {

    private final MeterRegistry registry;
    private final Map<String, Double> lastSuccess = new ConcurrentHashMap<>();

    public EnsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void auditEvent(String category, String outcome) {
        Counter.builder("ens.audit.events")
                .description("Events written to the ens-audit/1 trail")
                .tag("category", lower(category))
                .tag("outcome", lower(outcome))
                .register(registry)
                .increment();
    }

    public void auditForwardFailure(String output) {
        Counter.builder("ens.audit.forward.failures")
                .description("Failed attempts to forward audit events to an output")
                .tag("output", lower(output))
                .register(registry)
                .increment();
    }

    public void authAttempt(String method, boolean succeeded) {
        Counter.builder("ens.auth.attempts")
                .description("Sign-in attempts, by method and outcome")
                .tag("method", method == null ? "unknown" : lower(method))
                .tag("outcome", succeeded ? "success" : "failure")
                .register(registry)
                .increment();
    }

    public void digestTruncated() {
        Counter.builder("ens.certalert.digest.truncated")
                .description("Expiry round-ups that stopped at cert-alert.notifications.digest.scan-limit")
                .register(registry)
                .increment();
    }

    /** A job finished without failing, now. */
    public void jobSucceeded(String job, Instant at) {
        String name = lower(job);
        boolean first = lastSuccess.put(name, (double) at.getEpochSecond()) == null;
        if (first) {
            Gauge.builder("ens.certalert.job.last.success.seconds", lastSuccess, held -> held.getOrDefault(name, Double.NaN))
                    .description("When the job last finished without failing, as Unix time")
                    .tag("job", name)
                    .register(registry);
        }
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}

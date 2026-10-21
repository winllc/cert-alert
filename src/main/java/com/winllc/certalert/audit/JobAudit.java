package com.winllc.certalert.audit;

import com.winllc.certalert.observability.EnsMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * {@code job.completed} and {@code job.failed}: one event per run of a job, with its counts
 * and how long it took - never one per item. A run started from the UI is attributed to
 * whoever started it; a scheduled one to the system.
 */
@Component
public class JobAudit {

    private final AuditLog audit;
    private final ObjectProvider<EnsMetrics> metrics;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public JobAudit(AuditLog audit, ObjectProvider<EnsMetrics> metrics, Clock clock, ApplicationEventPublisher events) {
        this.audit = audit;
        this.metrics = metrics;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Published as a run of a job ends, for whatever else wants to know - the webhooks.
     *
     * @param counts what the result said, as details; empty for a failure
     * @param reason why it failed; null for a success
     */
    public record JobFinished(
            String job, boolean succeeded, String trigger, long durationMs, Map<String, Object> counts, String reason) {}

    private void publish(JobFinished finished) {
        try {
            events.publishEvent(finished);
        } catch (RuntimeException e) {
            // The run is over and audited whatever a listener makes of it.
            org.slf4j.LoggerFactory.getLogger(JobAudit.class).warn("A listener to the end of the {} run failed: {}",
                    finished.job(), e.toString());
        }
    }

    /**
     * Runs a job and records how it went. A failure is recorded and rethrown.
     *
     * @param counts what the result says, as details
     */
    public <T> T run(String job, Supplier<T> work, Function<T, Map<String, ?>> counts) {
        Instant started = Instant.now(clock);
        // Started by somebody signed in - from the UI or the API - or by the scheduler.
        boolean manual = AuditContext.currentActor().type() != AuditRecord.ActorType.SYSTEM;
        T result;
        try {
            result = work.get();
        } catch (RuntimeException e) {
            failed(job, started, manual, e);
            throw e;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        if (result != null && counts != null) {
            details.putAll(counts.apply(result));
        }
        completed(job, started, manual, details);
        return result;
    }

    /** A job's result record as details: its numbers, flags and names, not its nested objects. */
    public static Map<String, Object> countsOf(Object result) {
        Map<String, Object> counts = new LinkedHashMap<>();
        if (result == null || !result.getClass().isRecord()) {
            return counts;
        }
        for (java.lang.reflect.RecordComponent component : result.getClass().getRecordComponents()) {
            try {
                Object value = component.getAccessor().invoke(result);
                if (value instanceof Number || value instanceof Boolean || value instanceof CharSequence) {
                    counts.put(component.getName(), value instanceof CharSequence ? value.toString() : value);
                } else if (value instanceof Enum<?> e) {
                    counts.put(component.getName(), e.name());
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // a count that cannot be read is left out
            }
        }
        return counts;
    }

    /** Runs a job whose result is a record, recording its counts. */
    public <T> T run(String job, Supplier<T> work) {
        return run(job, work, JobAudit::countsOf);
    }

    public void completed(String job, Instant started, boolean manual, Map<String, ?> counts) {
        EnsMetrics counting = metrics.getIfAvailable();
        if (counting != null) {
            counting.jobSucceeded(job, Instant.now(clock));
        }
        AuditRecord event = AuditRecord.of(AuditRecord.Category.SYSTEM, "job.completed")
                .detail("job", job)
                .detail("trigger", manual ? "manual" : "schedule")
                .detail("durationMs", Duration.between(started, Instant.now(clock)).toMillis());
        if (!manual) {
            event.actor(AuditRecord.Actor.system("scheduler"));
        }
        Map<String, Object> published = new LinkedHashMap<>();
        if (counts != null) {
            counts.forEach((key, value) -> {
                if (!event.details().containsKey(key)) {
                    event.detail(key, value);
                }
                published.put(key, value);
            });
        }
        audit.recordQuietly(event);
        publish(new JobFinished(job, true, manual ? "manual" : "schedule",
                Duration.between(started, Instant.now(clock)).toMillis(), published, null));
    }

    public void failed(String job, Instant started, boolean manual, Throwable error) {
        String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        AuditRecord event = AuditRecord.of(AuditRecord.Category.SYSTEM, "job.failed")
                .outcome(AuditRecord.Outcome.FAILURE)
                .reason(reason)
                .detail("job", job)
                .detail("trigger", manual ? "manual" : "schedule")
                .detail("durationMs", Duration.between(started, Instant.now(clock)).toMillis())
                .detail("error", error.getClass().getSimpleName());
        if (!manual) {
            event.actor(AuditRecord.Actor.system("scheduler"));
        }
        audit.recordQuietly(event);
        publish(new JobFinished(job, false, manual ? "manual" : "schedule",
                Duration.between(started, Instant.now(clock)).toMillis(), Map.of(), reason));
    }
}

package com.winllc.certalert.cluster;

import com.winllc.certalert.audit.AuditContext;
import com.winllc.certalert.audit.AuditLog;
import com.winllc.certalert.audit.AuditRecord;
import com.winllc.certalert.audit.JobAudit;
import com.winllc.certalert.repository.JobRunRepository;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.concurrent.DelegatingSecurityContextRunnable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Jobs started by hand - "Sync directory", a revocation check, the round-up - run in the
 * background, and are asked after by id.
 *
 * <p>A sweep of a large directory takes longer than a proxy will hold a request open, so the
 * request that starts one answers at once with a run id (202), and the page asks
 * {@code /api/v1/jobs/{id}} how it is going until it has finished. The run is recorded in
 * {@code job_run}, so any replica can answer that.
 *
 * <p>A run takes the same lock as the job's schedule (see {@link JobLocks}): one started
 * while the job is already running - scheduled or by hand, here or on another replica - is
 * {@code SKIPPED} rather than run alongside it.
 *
 * <p>Audited as {@code job.requested} when it is asked for, with the run id, by whoever
 * asked and from where; and as {@code job.completed} or {@code job.failed} when it ends, as
 * any run of the job is.
 */
@Service
@EnableScheduling
public class JobRuns {

    private static final Logger log = LoggerFactory.getLogger(JobRuns.class);

    /** Runs started by hand at once on one replica; more wait their turn. */
    private static final int WORKERS = 2;

    /** Runs waiting for a worker, beyond which a request is refused. */
    private static final int QUEUE = 20;

    private final JobRunRepository runs;
    private final JobLocks locks;
    private final JobAudit jobs;
    private final AuditLog audit;
    private final ObjectMapper json;
    private final ClusterProperties properties;
    private final Clock clock;
    private final TransactionTemplate independent;

    private final Set<String> inProgress = ConcurrentHashMap.newKeySet();
    private final AtomicInteger waiting = new AtomicInteger();
    private final ExecutorService workers = Executors.newFixedThreadPool(WORKERS, new java.util.concurrent.ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "job-run-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    public JobRuns(
            JobRunRepository runs,
            JobLocks locks,
            JobAudit jobs,
            AuditLog audit,
            ObjectMapper json,
            ClusterProperties properties,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.runs = runs;
        this.locks = locks;
        this.jobs = jobs;
        this.audit = audit;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
        this.independent = new TransactionTemplate(transactions);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** A job that was already running when this run came to start it. */
    public static final class AlreadyRunningException extends RuntimeException {
        public AlreadyRunningException(String job) {
            super("The " + job + " is running, or its schedule has just run it, here or on another replica; "
                    + "this run did nothing");
        }
    }

    /** What one step of a run has to know to run itself: the run it is part of. */
    public final class Step {

        private final String runId;

        private Step(String runId) {
            this.runId = runId;
        }

        /**
         * Runs one job under its lock, audited as a run of it.
         *
         * @throws AlreadyRunningException where the job's lock is held
         */
        public <T> T exclusive(String lock, String job, Supplier<T> work) {
            return exclusive(lock, job, work, JobAudit::countsOf);
        }

        public <T> T exclusive(String lock, String job, Supplier<T> work, Function<T, Map<String, ?>> counts) {
            Object[] result = new Object[1];
            boolean ran = locks.runExclusively(lock, () -> {
                result[0] = jobs.run(job, work, value -> {
                    Map<String, Object> details = new LinkedHashMap<>(counts.apply(value));
                    details.put("runId", runId);
                    return details;
                });
            });
            if (!ran) {
                throw new AlreadyRunningException(job);
            }
            @SuppressWarnings("unchecked")
            T value = (T) result[0];
            return value;
        }
    }

    /**
     * Starts a run in the background and says where to ask after it.
     *
     * @param job what to call it, as the audit trail and the page do
     * @param body the run: each job in it through {@link Step#exclusive}
     * @throws RejectedExecutionException where too many runs are already waiting
     */
    public JobRunView submit(String job, Function<Step, ?> body) {
        if (waiting.incrementAndGet() > QUEUE + WORKERS) {
            waiting.decrementAndGet();
            throw new RejectedExecutionException("Too many jobs are waiting to run; try again shortly");
        }
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now(clock);
        AuditRecord.Actor actor = AuditContext.currentActor();
        JobRun run = independent.execute(status -> runs.save(new JobRun(id, job, actor.id(), now)));
        audit.recordQuietly(AuditRecord.of(AuditRecord.Category.SYSTEM, "job.requested")
                .detail("job", job)
                .detail("runId", id));
        Runnable task = new DelegatingSecurityContextRunnable(() -> execute(id, job, body));
        try {
            workers.execute(task);
        } catch (RejectedExecutionException e) {
            waiting.decrementAndGet();
            finish(id, JobRun.State.FAILED, null, "This replica is shutting down; start the job again");
            throw e;
        }
        return JobRunView.of(run, json);
    }

    private void execute(String id, String job, Function<Step, ?> body) {
        waiting.decrementAndGet();
        inProgress.add(id);
        try {
            independent.executeWithoutResult(status -> runs.findById(id).ifPresent(run -> run.started(Instant.now(clock))));
            Object result = body.apply(new Step(id));
            finish(id, JobRun.State.SUCCEEDED, json.writeValueAsString(result), null);
        } catch (AlreadyRunningException e) {
            finish(id, JobRun.State.SKIPPED, null, e.getMessage());
        } catch (RuntimeException e) {
            // Already audited as job.failed, and logged with context by the job itself.
            log.warn("The {} run {} failed: {}", job, id, e.toString());
            finish(id, JobRun.State.FAILED, null, describe(e));
        } finally {
            inProgress.remove(id);
        }
    }

    private void finish(String id, JobRun.State outcome, String result, String error) {
        try {
            independent.executeWithoutResult(status -> runs.findById(id)
                    .ifPresent(run -> run.finished(outcome, result, error, Instant.now(clock))));
        } catch (RuntimeException e) {
            log.error("Could not record how the run {} ended ({})", id, outcome, e);
        }
    }

    /** How a run is going, or empty where there is no such run (or it is long forgotten). */
    public Optional<JobRunView> find(String id) {
        return runs.findById(id).map(run -> JobRunView.of(run, json));
    }

    /** The most recent runs, newest first. */
    public List<JobRunView> recent(int limit) {
        return runs.findByOrderByRequestedAtDesc(PageRequest.of(0, Math.clamp(limit, 1, 100))).stream()
                .map(run -> JobRunView.of(run, json))
                .toList();
    }

    /**
     * Says this replica's runs are still going, fails those whose replica has stopped
     * saying so, and forgets old ones. On every replica: each beats for its own runs, and
     * failing or forgetting the same row twice is harmless.
     */
    @Scheduled(fixedDelayString = "PT30S", initialDelayString = "PT30S")
    public void housekeeping() {
        Instant now = Instant.now(clock);
        try {
            independent.executeWithoutResult(status -> {
                if (!inProgress.isEmpty()) {
                    runs.beat(List.copyOf(inProgress), now);
                }
                int abandoned = runs.abandon(
                        List.of(JobRun.State.QUEUED, JobRun.State.RUNNING),
                        JobRun.State.FAILED,
                        now.minus(properties.getStaleRunAfter()),
                        now);
                if (abandoned > 0) {
                    log.warn("Marked {} job run(s) failed: the replica running them stopped", abandoned);
                }
                runs.deleteRequestedBefore(now.minus(properties.getRunRetention()));
            });
        } catch (RuntimeException e) {
            log.warn("Job run housekeeping failed; it is tried again shortly", e);
        }
    }

    @PreDestroy
    void stop() {
        workers.shutdownNow();
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}

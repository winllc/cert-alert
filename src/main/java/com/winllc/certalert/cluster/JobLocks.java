package com.winllc.certalert.cluster;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.support.KeepAliveLockProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One run of a job at a time, across every replica: a lock per job, held in the database
 * ({@code shedlock}, see V21).
 *
 * <p>Every scheduled job takes its lock before it runs, and so does the same job started by
 * hand, so "Sync directory" pressed while the nightly sweep is running stands down rather
 * than sweeping alongside it - on this replica or another. A job that finds its lock held
 * does nothing and says so.
 *
 * <p>The lock is renewed while the job runs, so a sweep of a large directory that outlasts
 * {@code cert-alert.cluster.lock-at-most-for} keeps it; a replica that dies holding one
 * releases it once that has passed. The database's clock decides, not the replicas', so
 * replicas whose clocks disagree still agree on who holds what.
 *
 * <p>Not reentrant: a job that asks for a lock it already holds is refused it, as any other
 * caller would be.
 */
@Component
public class JobLocks {

    private static final Logger log = LoggerFactory.getLogger(JobLocks.class);

    private static final Duration MINIMUM = Duration.ofSeconds(30);

    private final LockProvider provider;
    private final Duration lockAtMostFor;
    private final Duration scheduledAtLeastFor;
    private final ScheduledExecutorService renewals = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "job-lock-renewal");
        thread.setDaemon(true);
        return thread;
    });

    public JobLocks(DataSource dataSource, ClusterProperties properties) {
        this.lockAtMostFor = properties.getLockAtMostFor().compareTo(MINIMUM) < 0
                ? MINIMUM
                : properties.getLockAtMostFor();
        Duration atLeast = properties.getScheduledLockAtLeastFor();
        this.scheduledAtLeastFor = atLeast == null || atLeast.isNegative() ? Duration.ZERO : atLeast;
        JdbcTemplateLockProvider jdbc = new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName("shedlock")
                .usingDbTime()
                .build());
        this.provider = new KeepAliveLockProvider(jdbc, renewals);
    }

    /**
     * Runs the work if nobody else is running the job named, on this replica or any other.
     *
     * @return what it returned, or empty where the job was already running and this did
     *     nothing (a job that returns null also comes back empty)
     */
    public <T> Optional<T> runExclusively(String job, Supplier<T> work) {
        return runExclusively(job, Duration.ZERO, work);
    }

    /**
     * A scheduled run: as {@link #runExclusively(String, Runnable)}, and the lock is then held
     * for {@code cert-alert.cluster.scheduled-lock-at-least-for} from when it was taken, however
     * soon the job finished - so a replica whose schedule fires a little later does not run the
     * same firing again.
     */
    public boolean runScheduled(String job, Runnable work) {
        return runExclusively(job, scheduledAtLeastFor, () -> {
            work.run();
            return Boolean.TRUE;
        }).isPresent();
    }

    private <T> Optional<T> runExclusively(String job, Duration atLeastFor, Supplier<T> work) {
        Duration atMost = atLeastFor.compareTo(lockAtMostFor) >= 0 ? atLeastFor.plus(lockAtMostFor) : lockAtMostFor;
        Optional<SimpleLock> lock = provider.lock(new LockConfiguration(Instant.now(), job, atMost, atLeastFor));
        if (lock.isEmpty()) {
            log.info("Not running {}: it is already running", job);
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(work.get());
        } finally {
            try {
                lock.get().unlock();
            } catch (RuntimeException e) {
                // Released when lock-at-most-for passes instead.
                log.warn("Could not release the lock on {}: {}", job, e.getMessage());
            }
        }
    }

    /** Whether the work ran: for work that returns nothing. */
    public boolean runExclusively(String job, Runnable work) {
        return runExclusively(job, () -> {
            work.run();
            return Boolean.TRUE;
        }).isPresent();
    }

    @PreDestroy
    void stop() {
        renewals.shutdownNow();
    }
}

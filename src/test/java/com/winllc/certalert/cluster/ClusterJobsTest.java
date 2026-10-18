package com.winllc.certalert.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.domain.SyncJob;
import com.winllc.certalert.repository.JobRunRepository;
import com.winllc.certalert.repository.AuditEventRepository;
import com.winllc.certalert.support.JobRunsAwait;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * More than one replica: one run of a job at a time across all of them, and jobs started by
 * hand that answer at once and are asked after.
 *
 * <p>A second replica is stood in for by a second {@link JobLocks} on the same database -
 * which is all two replicas share.
 */
@SpringBootTest
@ActiveProfiles("test")
class ClusterJobsTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JobLocks locks;

    @Autowired
    private JobRuns jobRuns;

    @Autowired
    private JobRunRepository runs;

    @Autowired
    private AuditEventRepository audit;

    @Autowired
    private TransactionTemplate transactions;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @AfterEach
    void clear() {
        runs.deleteAll();
    }

    private static RequestPostProcessor admin() {
        return user("admin").roles("USER", "ADMIN");
    }

    @Test
    void aJobRunsOnOneReplicaAtATime() throws Exception {
        JobLocks otherReplica = new JobLocks(dataSource, new ClusterProperties());
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread other = new Thread(() -> otherReplica.runExclusively("test-job", () -> {
            holding.countDown();
            await(release);
        }));
        other.start();
        try {
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            AtomicBoolean ran = new AtomicBoolean();
            assertThat(locks.runExclusively("test-job", () -> ran.set(true))).isFalse();
            assertThat(ran).isFalse();
            // A different job is not held up by it.
            assertThat(locks.runExclusively("another-job", () -> ran.set(true))).isTrue();
        } finally {
            release.countDown();
            other.join(10_000);
        }
        // Released when the other replica finished, so the next run goes ahead.
        assertThat(locks.runExclusively("test-job", () -> {})).isTrue();
    }

    @Test
    void aRunStartedByHandAnswersAtOnceAndIsAskedAfter() throws Exception {
        long requested = audit.count();
        JobRunsAwait.finished(mockMvc,
                        mockMvc.perform(post("/api/v1/sync/refresh").with(admin()).with(csrf()))
                                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/jobs/")))
                                .andExpect(jsonPath("$.job").value("expiry refresh"))
                                .andExpect(jsonPath("$.statusUrl").exists()),
                        admin())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.finished").value(true))
                .andExpect(jsonPath("$.result.job").value("REFRESH"))
                .andExpect(jsonPath("$.result.entriesSeen").exists());
        assertThat(audit.findAll()).anySatisfy(event -> assertThat(event.getLine())
                .contains("\"action\":\"job.requested\""));
        assertThat(audit.findAll()).anySatisfy(event -> assertThat(event.getLine())
                .contains("\"action\":\"job.completed\"")
                .contains("\"runId\""));
        assertThat(audit.count()).isGreaterThan(requested);
    }

    /** "Sync directory" pressed while the scheduled run is going - on any replica - stands down. */
    @Test
    void aRunStartedWhileTheJobIsRunningIsSkipped() throws Exception {
        JobLocks otherReplica = new JobLocks(dataSource, new ClusterProperties());
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread scheduled = new Thread(() -> otherReplica.runExclusively(SyncJob.REFRESH.lockName(), () -> {
            holding.countDown();
            await(release);
        }));
        scheduled.start();
        try {
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            JobRunsAwait.finished(mockMvc,
                            mockMvc.perform(post("/api/v1/sync/refresh").with(admin()).with(csrf())), admin())
                    .andExpect(jsonPath("$.state").value("SKIPPED"))
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("already going")));
        } finally {
            release.countDown();
            scheduled.join(10_000);
        }
    }

    @Test
    void aRunWhoseReplicaStoppedIsMarkedFailed() {
        Instant long_ago = Instant.now().minus(Duration.ofHours(1));
        transactions.executeWithoutResult(status -> {
            JobRun run = new JobRun("00000000-0000-0000-0000-000000000001", "user sweep", "admin", long_ago);
            run.started(long_ago);
            runs.save(run);
        });
        jobRuns.housekeeping();
        JobRun run = runs.findById("00000000-0000-0000-0000-000000000001").orElseThrow();
        assertThat(run.getState()).isEqualTo(JobRun.State.FAILED);
        assertThat(run.getError()).contains("stopped");
    }

    @Test
    void anUnknownRunIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/jobs/nope").with(admin())).andExpect(status().isNotFound());
    }

    @Test
    void aReaderCannotStartOne() throws Exception {
        mockMvc.perform(post("/api/v1/sync/refresh").with(user("reader").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

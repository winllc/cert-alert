package com.winllc.certalert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;

/**
 * A record of one scheduled run.
 *
 * <p>Against a directory of this size a sweep is a long job, and "did last night's sync
 * finish, and what did it do" is the first question anyone asks. It also gives the prune
 * job something to check: entries should only be deleted on the evidence of syncs that
 * actually completed.
 */
@Entity
@Table(name = "sync_run", indexes = @Index(name = "idx_sync_run_job_started", columnList = "job, started_at"))
public class SyncRun {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "sync_run_seq")
    @SequenceGenerator(name = "sync_run_seq", sequenceName = "sync_run_seq", allocationSize = 1)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SyncJob job;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SyncStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "entries_seen", nullable = false)
    private int entriesSeen;

    @Column(name = "entries_created", nullable = false)
    private int entriesCreated;

    @Column(name = "certificates_cached", nullable = false)
    private int certificatesCached;

    @Column(name = "certificates_removed", nullable = false)
    private int certificatesRemoved;

    @Column(name = "alerts_raised", nullable = false)
    private int alertsRaised;

    @Column(name = "entries_pruned", nullable = false)
    private int entriesPruned;

    @Column(nullable = false)
    private int errors;

    @Column(length = 2000)
    private String message;

    protected SyncRun() {
        // for JPA
    }

    public static SyncRun started(SyncJob job, Instant startedAt) {
        SyncRun run = new SyncRun();
        run.job = job;
        run.status = SyncStatus.RUNNING;
        run.startedAt = startedAt;
        return run;
    }

    public void succeeded(Instant finishedAt) {
        this.status = errors > 0 ? SyncStatus.COMPLETED_WITH_ERRORS : SyncStatus.COMPLETED;
        this.finishedAt = finishedAt;
    }

    public void failed(Instant finishedAt, String message) {
        this.status = SyncStatus.FAILED;
        this.finishedAt = finishedAt;
        this.message = truncate(message);
    }

    public void record(int entriesSeen, int entriesCreated, int certificatesCached, int certificatesRemoved,
            int alertsRaised, int errors) {
        this.entriesSeen = entriesSeen;
        this.entriesCreated = entriesCreated;
        this.certificatesCached = certificatesCached;
        this.certificatesRemoved = certificatesRemoved;
        this.alertsRaised = alertsRaised;
        this.errors = errors;
    }

    public void recordPruned(int entriesPruned) {
        this.entriesPruned = entriesPruned;
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= 2000) {
            return value;
        }
        return value.substring(0, 1997) + "...";
    }

    public Duration duration() {
        return finishedAt == null ? Duration.ZERO : Duration.between(startedAt, finishedAt);
    }

    public Long getId() {
        return id;
    }

    public SyncJob getJob() {
        return job;
    }

    public SyncStatus getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public int getEntriesSeen() {
        return entriesSeen;
    }

    public int getEntriesCreated() {
        return entriesCreated;
    }

    public int getCertificatesCached() {
        return certificatesCached;
    }

    public int getCertificatesRemoved() {
        return certificatesRemoved;
    }

    public int getAlertsRaised() {
        return alertsRaised;
    }

    public int getEntriesPruned() {
        return entriesPruned;
    }

    public int getErrors() {
        return errors;
    }

    public String getMessage() {
        return message;
    }
}

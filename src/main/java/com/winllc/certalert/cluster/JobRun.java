package com.winllc.certalert.cluster;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A job somebody started by hand, and what became of it (see {@link JobRuns}).
 *
 * <p>In the database rather than in memory so that whichever replica the page's next
 * request reaches can say how the run is going, not only the one running it.
 */
@Entity
@Table(name = "job_run")
public class JobRun {

    /** Longest error kept; the log has the rest. */
    static final int MAX_ERROR = 1000;

    /** Where a run has got to. */
    public enum State {
        /** Accepted, not started yet. */
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        /** Not run, because a run of the same job was already going - here or on another replica. */
        SKIPPED;

        public boolean finished() {
            return this == SUCCEEDED || this == FAILED || this == SKIPPED;
        }
    }

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 64)
    private String job;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state;

    @Column(name = "requested_by", length = 512)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "heartbeat_at", nullable = false)
    private Instant heartbeatAt;

    /** What the job returned, as JSON. */
    @Column(length = 1_000_000)
    private String result;

    @Column(length = MAX_ERROR)
    private String error;

    protected JobRun() {
        // for JPA
    }

    JobRun(String id, String job, String requestedBy, Instant now) {
        this.id = id;
        this.job = job;
        this.state = State.QUEUED;
        this.requestedBy = requestedBy == null || requestedBy.length() <= 512 ? requestedBy : requestedBy.substring(0, 512);
        this.requestedAt = now;
        this.heartbeatAt = now;
    }

    void started(Instant now) {
        this.state = State.RUNNING;
        this.startedAt = now;
        this.heartbeatAt = now;
    }

    void finished(State outcome, String result, String error, Instant now) {
        this.state = outcome;
        this.result = result;
        this.error = error == null || error.length() <= MAX_ERROR ? error : error.substring(0, MAX_ERROR);
        this.finishedAt = now;
        this.heartbeatAt = now;
    }

    public String getId() {
        return id;
    }

    public String getJob() {
        return job;
    }

    public State getState() {
        return state;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getHeartbeatAt() {
        return heartbeatAt;
    }

    public String getResult() {
        return result;
    }

    public String getError() {
        return error;
    }
}

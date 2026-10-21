-- A job somebody started by hand - "Sync directory", a revocation check, the round-up - and
-- what became of it.
--
-- The request that starts one answers at once (202) with this row's id, and the job runs in
-- the background; the page asks after the id until the run has finished. Kept here rather
-- than in memory so that any replica can answer that question, whichever ran the job.
--
-- heartbeat_at is moved on while the run is going. A run whose heartbeat has stopped - the
-- replica running it was stopped or died - is marked FAILED by whichever replica notices.

CREATE TABLE job_run (
    id           VARCHAR(36)     PRIMARY KEY,
    job          VARCHAR(64)     NOT NULL,
    state        VARCHAR(16)     NOT NULL,
    requested_by VARCHAR(512),
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at   TIMESTAMP WITH TIME ZONE,
    finished_at  TIMESTAMP WITH TIME ZONE,
    heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL,
    result       VARCHAR(1000000),
    error        VARCHAR(1000)
);

CREATE INDEX idx_job_run_requested ON job_run (requested_at);
CREATE INDEX idx_job_run_state ON job_run (state, heartbeat_at);

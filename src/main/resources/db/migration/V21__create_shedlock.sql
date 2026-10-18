-- Who holds which job right now, so that with more than one replica a job runs on one of
-- them at a time: ShedLock's table, in the shape its JDBC provider expects.
--
-- name is the job (user-sweep, revocation, mail-outbox, ...). A replica takes a job by
-- moving lock_until into the future where it is in the past, and keeps moving it while the
-- job runs; one that dies holding a lock loses it when lock_until passes. Times are UTC, as
-- the database itself reports them (the provider uses the database's clock, not the
-- replicas'), and plain TIMESTAMP as ShedLock documents for both databases.

CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);

-- Saved views, scheduled reports, and attachments on outbound mail.
--
-- saved_view: a named set of filters one person keeps for the certificate inventory - the
-- page's query string, checked when it is saved. Theirs alone: owner is who saved it, as the
-- audit trail names them.
--
-- scheduled_report: an administrator's standing request to email the inventory, narrowed by
-- the same filters, as CSV or Excel, daily, weekly or monthly at an hour (UTC). The runner
-- takes the database lock every job takes, so one replica sends each report once.
--
-- outbound_email gains an attachment: the report's file, sent through the same outbox (after
-- the commit, retried) as everything else.

CREATE SEQUENCE saved_view_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE saved_view (
    id          BIGINT PRIMARY KEY,
    owner       VARCHAR(320) NOT NULL,
    page        VARCHAR(32) NOT NULL,
    name        VARCHAR(120) NOT NULL,
    query       VARCHAR(2000) NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_saved_view_name UNIQUE (owner, page, name)
);

CREATE SEQUENCE scheduled_report_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE scheduled_report (
    id            BIGINT PRIMARY KEY,
    owner         VARCHAR(320) NOT NULL,
    name          VARCHAR(120) NOT NULL,
    query         VARCHAR(2000) NOT NULL,
    format        VARCHAR(8) NOT NULL,
    recipients    VARCHAR(2000) NOT NULL,
    frequency     VARCHAR(16) NOT NULL,
    day_of_week   INTEGER,
    day_of_month  INTEGER,
    hour_utc      INTEGER NOT NULL,
    enabled       BOOLEAN NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    next_run_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_run_at   TIMESTAMP WITH TIME ZONE,
    last_rows     BIGINT,
    last_error    VARCHAR(1000)
);

CREATE INDEX idx_scheduled_report_due ON scheduled_report (enabled, next_run_at);

ALTER TABLE outbound_email ADD COLUMN attachment_name VARCHAR(255);
ALTER TABLE outbound_email ADD COLUMN attachment_type VARCHAR(128);
ALTER TABLE outbound_email ADD COLUMN attachment BYTEA;

-- Durable position for the changelog connector.
--
-- One row, so the connector resumes where it left off rather than replaying history or
-- silently skipping what happened while it was down. The bounds seen on the last poll are
-- kept alongside it so a trimmed changelog - the directory discarding entries the connector
-- had not reached - can be detected rather than quietly leaving the cache stale.

CREATE SEQUENCE changelog_cursor_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE changelog_cursor (
    id                     BIGINT PRIMARY KEY,
    name                   VARCHAR(64) NOT NULL,
    last_change_number     BIGINT NOT NULL DEFAULT 0,
    -- What the directory reported it still holds, as of the last poll.
    first_available_number BIGINT,
    last_available_number  BIGINT,
    changes_applied        BIGINT NOT NULL DEFAULT 0,
    changes_ignored        BIGINT NOT NULL DEFAULT 0,
    errors                 BIGINT NOT NULL DEFAULT 0,
    gaps_detected          BIGINT NOT NULL DEFAULT 0,
    started_at             TIMESTAMP WITH TIME ZONE,
    last_polled_at         TIMESTAMP WITH TIME ZONE,
    last_change_at         TIMESTAMP WITH TIME ZONE,
    last_error             VARCHAR(2000),
    version                BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_changelog_cursor_name UNIQUE (name)
);

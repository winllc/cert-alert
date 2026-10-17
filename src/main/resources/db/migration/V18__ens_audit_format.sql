-- The audit trail moves to the shared ENS format, ens-audit/1: one JSON object per event,
-- numbered without gaps and chained by SHA-256 so that an edited or deleted record shows.
--
-- The same table, not a second one beside it. Every row keeps what it had, so an entry's
-- history on its details page reads as it always did; the new columns are the event as the
-- format describes it, and line is the event itself, stored verbatim so that verification
-- never depends on serialising it again.
--
-- Rows written before this migration have no seq yet. The application numbers and chains
-- them at startup, oldest first, before it writes anything new (AuditLog): a SHA-256 chain
-- is not something to compute portably in SQL, and the product version and installation id
-- that go into each event are the application's to know.

-- Entry changes keep their kind, subject and sentence; the other events have none of them.
ALTER TABLE audit_event ALTER COLUMN action DROP NOT NULL;
ALTER TABLE audit_event ALTER COLUMN subject_type DROP NOT NULL;
ALTER TABLE audit_event ALTER COLUMN subject_dn DROP NOT NULL;
ALTER TABLE audit_event ALTER COLUMN summary DROP NOT NULL;

ALTER TABLE audit_event ADD COLUMN seq BIGINT;
ALTER TABLE audit_event ADD COLUMN event_id VARCHAR(36);
ALTER TABLE audit_event ADD COLUMN category VARCHAR(16);
ALTER TABLE audit_event ADD COLUMN event_action VARCHAR(80);
ALTER TABLE audit_event ADD COLUMN outcome VARCHAR(8);
ALTER TABLE audit_event ADD COLUMN severity VARCHAR(8);
ALTER TABLE audit_event ADD COLUMN actor_type VARCHAR(16);
ALTER TABLE audit_event ADD COLUMN actor_id VARCHAR(320);
ALTER TABLE audit_event ADD COLUMN target_type VARCHAR(32);
ALTER TABLE audit_event ADD COLUMN target_id VARCHAR(512);
ALTER TABLE audit_event ADD COLUMN source_ip VARCHAR(64);
ALTER TABLE audit_event ADD COLUMN hash VARCHAR(64);
ALTER TABLE audit_event ADD COLUMN line VARCHAR(100000);

ALTER TABLE audit_event ADD CONSTRAINT uk_audit_event_seq UNIQUE (seq);
CREATE INDEX idx_audit_event_category ON audit_event (category);
CREATE INDEX idx_audit_event_event_action ON audit_event (event_action);
CREATE INDEX idx_audit_event_outcome ON audit_event (outcome);
CREATE INDEX idx_audit_event_severity ON audit_event (severity);
CREATE INDEX idx_audit_event_actor_id ON audit_event (actor_id);
CREATE INDEX idx_audit_event_target ON audit_event (target_type, target_id);
CREATE INDEX idx_audit_event_source_ip ON audit_event (source_ip);

-- The head of the chain: the last seq handed out and its hash. One row. Every writer locks
-- it (SELECT ... FOR UPDATE) for the moment it takes to append, which is what keeps seq
-- gapless and the chain unbroken across threads and across instances sharing a database.
CREATE TABLE audit_chain_head (
    id        INTEGER PRIMARY KEY,
    last_seq  BIGINT NOT NULL,
    last_hash VARCHAR(64) NOT NULL
);
INSERT INTO audit_chain_head (id, last_seq, last_hash)
VALUES (1, 0, '0000000000000000000000000000000000000000000000000000000000000000');

-- How far each forwarding output (Splunk HEC, syslog) has got: the last seq it delivered.
-- Kept here so a restart resumes rather than resending or skipping.
CREATE TABLE audit_delivery (
    output         VARCHAR(32) PRIMARY KEY,
    last_seq       BIGINT NOT NULL,
    delivered_at   TIMESTAMP WITH TIME ZONE,
    last_error     VARCHAR(1000),
    last_error_at  TIMESTAMP WITH TIME ZONE,
    failing_since  TIMESTAMP WITH TIME ZONE,
    alerted        BOOLEAN NOT NULL
);

-- Bearer tokens for the SIEM pull API. Only the SHA-256 of a token is kept; the token itself
-- is shown once, when it is created.
CREATE SEQUENCE audit_api_token_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE audit_api_token (
    id           BIGINT PRIMARY KEY,
    name         VARCHAR(120) NOT NULL,
    token_hash   VARCHAR(64) NOT NULL,
    prefix       VARCHAR(16) NOT NULL,
    created_by   VARCHAR(320) NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE,
    revoked_at   TIMESTAMP WITH TIME ZONE,
    revoked_by   VARCHAR(320),
    CONSTRAINT uk_audit_api_token_hash UNIQUE (token_hash)
);

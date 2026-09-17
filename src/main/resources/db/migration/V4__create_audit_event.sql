-- What happened to a directory entry, kept so it can be answered for later.
--
-- The subject is a type and an id with no foreign key, deliberately. An audit record has to
-- outlive what it describes - the most interesting record of all is the one saying an entry
-- was deleted - and a foreign key would either take the record with it or refuse the
-- deletion. The dn and name are copied in for the same reason: after a prune they are all
-- that is left to read.
--
-- What is recorded is changes. A sweep that finds a hundred thousand entries exactly as it
-- left them writes nothing here; sync_run already records the sweep.

CREATE SEQUENCE audit_event_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE audit_event (
    id                      BIGINT PRIMARY KEY,
    occurred_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    action                  VARCHAR(40) NOT NULL,
    subject_type            VARCHAR(16) NOT NULL,
    subject_id              BIGINT,
    subject_dn              VARCHAR(512) NOT NULL,
    subject_name            VARCHAR(320),
    actor                   VARCHAR(320) NOT NULL,
    summary                 VARCHAR(1000) NOT NULL,
    certificate_fingerprint VARCHAR(64),
    channel                 VARCHAR(64),
    target                  VARCHAR(320)
);

-- The history of one entry, newest first, which is the only query the UI makes.
CREATE INDEX idx_audit_event_subject ON audit_event (subject_type, subject_id, occurred_at);
CREATE INDEX idx_audit_event_occurred ON audit_event (occurred_at);
CREATE INDEX idx_audit_event_action ON audit_event (action);

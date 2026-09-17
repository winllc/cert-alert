-- Something somebody needs to be told, addressed to one person.
--
-- An alert is raised once about a certificate; a notification is one person's copy of it. A
-- server with four points of contact produces four, because being told is a thing that
-- happens to a person, and so is having read it.
--
-- The recipient is held twice over: the person in the directory, which is what makes it
-- appear when they sign in, and the address an email would go to. A distribution list
-- nobody has claimed has the second and not the first.
--
-- recipient_user_id is not a foreign key, for the same reason the audit trail's subject is
-- not: a notification about an entry that has since been pruned is still a record of
-- somebody having been told.

CREATE SEQUENCE notification_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE notification (
    id                      BIGINT PRIMARY KEY,
    recipient_user_id       BIGINT,
    recipient_address       VARCHAR(320),
    kind                    VARCHAR(32) NOT NULL,
    subject_type            VARCHAR(16) NOT NULL,
    subject_id              BIGINT,
    subject_dn              VARCHAR(512) NOT NULL,
    subject_name            VARCHAR(320),
    certificate_fingerprint VARCHAR(64),
    severity                VARCHAR(16) NOT NULL,
    message                 VARCHAR(1000) NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    read_at                 TIMESTAMP WITH TIME ZONE,
    emailed_at              TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_notification_recipient CHECK (recipient_user_id IS NOT NULL OR recipient_address IS NOT NULL)
);

-- What somebody sees when they sign in, and the badge on it.
CREATE INDEX idx_notification_recipient ON notification (recipient_user_id, created_at);
CREATE INDEX idx_notification_unread ON notification (recipient_user_id, read_at);
-- What the digest has still to send.
CREATE INDEX idx_notification_unsent ON notification (emailed_at, created_at);
CREATE INDEX idx_notification_subject ON notification (subject_type, subject_id);

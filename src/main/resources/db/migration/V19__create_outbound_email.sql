-- Email waiting to be sent, and what became of it: an outbox.
--
-- A message is written here in the same transaction as whatever decided to send it - the
-- round-up's notifications, the sweep that raised an alert - and sent after that commits,
-- by the application, outside any transaction. So a mail server that is down loses nothing:
-- the message is still here, and is tried again later, further apart each time, until it
-- goes or until it is old enough to give up on. And a mail server that is slow holds no
-- database transaction open while it thinks about it.
--
-- supersede_key names what a message is the latest version of - one person's round-up of
-- their own certificates, say. Queueing a new one retires any older one still waiting, so a
-- mail server that comes back after three days delivers today's round-up, not all three.
--
-- notification_id is not a foreign key, for the same reason the notification's subject is
-- not: what was sent is a record in its own right.

CREATE SEQUENCE outbound_email_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE outbound_email (
    id              BIGINT PRIMARY KEY,
    kind            VARCHAR(16) NOT NULL,
    state           VARCHAR(16) NOT NULL,
    sender          VARCHAR(320) NOT NULL,
    recipients      VARCHAR(4000) NOT NULL,
    subject         VARCHAR(1000) NOT NULL,
    text_body       VARCHAR(10485760) NOT NULL,
    html_body       VARCHAR(10485760),
    supersede_key   VARCHAR(512),
    notification_id BIGINT,
    attempts        INTEGER NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    sent_at         TIMESTAMP WITH TIME ZONE,
    last_error      VARCHAR(1000)
);

-- What is due, oldest first.
CREATE INDEX idx_outbound_email_due ON outbound_email (state, next_attempt_at);
-- What a new round-up replaces.
CREATE INDEX idx_outbound_email_supersede ON outbound_email (supersede_key, state);
CREATE INDEX idx_outbound_email_created ON outbound_email (created_at);

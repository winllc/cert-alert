-- Addresses a person answers to that the directory does not publish for them.
--
-- The directory holds up to five addresses per person and all five are indexed, but a
-- server's serverPOC is written by whoever runs the server, and they write what they use:
-- an old address, a role address, or the team's distribution list. A server named after a
-- list has no contact at all as far as the directory is concerned.
--
-- An address here joins the set a serverPOC is matched against, so from then on that server
-- is one of theirs. A GROUP address is expected to belong to several people at once - that
-- is what a list is - so the uniqueness is per person, not global.

CREATE SEQUENCE user_email_alias_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE user_email_alias (
    id       BIGINT PRIMARY KEY,
    user_id  BIGINT NOT NULL,
    address  VARCHAR(320) NOT NULL,
    kind     VARCHAR(16) NOT NULL,
    label    VARCHAR(255),
    added_by VARCHAR(320),
    added_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_user_email_alias_user FOREIGN KEY (user_id)
        REFERENCES directory_user (id) ON DELETE CASCADE,
    CONSTRAINT uk_user_email_alias UNIQUE (user_id, address)
);

CREATE INDEX idx_user_email_alias_user ON user_email_alias (user_id);
-- Who answers to this address: for a list, everyone on it.
CREATE INDEX idx_user_email_alias_address ON user_email_alias (address);

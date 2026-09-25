-- Addresses read out of attributes a deployment named beyond the five in the schema.
--
-- The IC FSD defines mail, icEmail, internetEmail, niprnetEmail and siprnetEmail, and a
-- real directory usually carries more: an agency's own alternateMail, a mail-system
-- attribute that predates the schema, a team address kept on the person who owns it. A
-- serverPOC written with one of those names nobody, as far as the join is concerned, and
-- the server it belongs to has no contact.
--
-- Kept here rather than folded straight into directory_user_identifier because that set is
-- rebuilt from what the entry holds - by a sweep, and again whenever somebody edits the
-- addresses added to a person. Anything only the directory knows has to survive the second
-- of those, or adding one alias would quietly drop it until the next sweep.

CREATE TABLE directory_user_email (
    user_id BIGINT NOT NULL,
    address VARCHAR(320) NOT NULL,
    CONSTRAINT fk_user_email_user FOREIGN KEY (user_id)
        REFERENCES directory_user (id) ON DELETE CASCADE
);

CREATE INDEX idx_user_email_user ON directory_user_email (user_id);
-- Read on every sweep to rebuild the identifier set for the point-of-contact join.
CREATE INDEX idx_user_email_value ON directory_user_email (address);

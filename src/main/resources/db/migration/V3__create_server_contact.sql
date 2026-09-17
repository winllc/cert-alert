-- Points of contact managed here rather than scraped from the directory.
--
-- The directory's own serverPOC lives in directory_server_poc and is replaced wholesale by
-- every sweep. These rows are this application's own data: a sweep leaves them alone, and
-- they are what somebody edits when the directory's answer is wrong, absent, or not theirs
-- to change.
--
-- A contact is a person the directory knows (user_id), a bare address (email), or both -
-- a person carries their address so an alert has somewhere to go without loading them.

CREATE SEQUENCE server_contact_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE server_contact (
    id        BIGINT PRIMARY KEY,
    server_id BIGINT NOT NULL,
    user_id   BIGINT,
    email     VARCHAR(320),
    added_by  VARCHAR(320),
    added_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_server_contact_server FOREIGN KEY (server_id)
        REFERENCES directory_server (id) ON DELETE CASCADE,
    -- A person pruned from the directory takes their contact rows with them: the link was
    -- the whole of what it recorded. An address added on its own is not a person and is
    -- never touched by a prune.
    CONSTRAINT fk_server_contact_user FOREIGN KEY (user_id)
        REFERENCES directory_user (id) ON DELETE CASCADE,
    CONSTRAINT ck_server_contact_target CHECK (user_id IS NOT NULL OR email IS NOT NULL),
    -- Both allow several rows with a NULL, on PostgreSQL and H2 alike, which is what makes
    -- one constraint per kind work: many addresses with no person, many people with no
    -- published address, but never the same person or the same address twice on a server.
    CONSTRAINT uk_server_contact_user UNIQUE (server_id, user_id),
    CONSTRAINT uk_server_contact_email UNIQUE (server_id, email)
);

CREATE INDEX idx_server_contact_server ON server_contact (server_id);
CREATE INDEX idx_server_contact_user ON server_contact (user_id);
CREATE INDEX idx_server_contact_email ON server_contact (email);

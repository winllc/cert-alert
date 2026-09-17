-- A piece of work, with the people and servers that belong to it.
--
-- The directory knows that a person is in an organization and that a server has a point of
-- contact. It does not know that these six servers and these four people are one system
-- that gets renewed together. That grouping is this application's, and it is what turns
-- "forty certificates expire this month" into "the payroll migration expires this month".

CREATE SEQUENCE project_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE project (
    id          BIGINT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description VARCHAR(2000),
    created_by  VARCHAR(320),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_project_name UNIQUE (name)
);

CREATE INDEX idx_project_name ON project (name);

-- Both sides cascade: an entry pruned from the directory leaves its projects, and deleting
-- a project does not leave rows pointing at nothing. A prune is a bulk delete that JPA
-- never sees, so this has to be the database's job rather than the mapping's.
CREATE TABLE project_user (
    project_id BIGINT NOT NULL,
    user_id    BIGINT NOT NULL,
    PRIMARY KEY (project_id, user_id),
    CONSTRAINT fk_project_user_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT fk_project_user_user FOREIGN KEY (user_id) REFERENCES directory_user (id) ON DELETE CASCADE
);

CREATE TABLE project_server (
    project_id BIGINT NOT NULL,
    server_id  BIGINT NOT NULL,
    PRIMARY KEY (project_id, server_id),
    CONSTRAINT fk_project_server_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT fk_project_server_server FOREIGN KEY (server_id) REFERENCES directory_server (id) ON DELETE CASCADE
);

CREATE INDEX idx_project_user_user ON project_user (user_id);
CREATE INDEX idx_project_server_server ON project_server (server_id);

-- Who runs a project, as distinct from who is in it.
--
-- Membership says what a person has to do with a piece of work; it is a grouping, and that
-- is all it should carry. Deciding who hears that a certificate on one of the project's
-- servers is expiring, and who may change that list, is a different thing and now has a
-- role of its own: a project administrator.
--
-- A separate table rather than a column on project_user, so every query that asks who is in
-- a project goes on working unchanged - an administrator is a member too, and the
-- application keeps it that way.

CREATE TABLE project_admin (
    project_id BIGINT NOT NULL,
    user_id    BIGINT NOT NULL,
    PRIMARY KEY (project_id, user_id),
    CONSTRAINT fk_project_admin_project FOREIGN KEY (project_id) REFERENCES project (id) ON DELETE CASCADE,
    CONSTRAINT fk_project_admin_user FOREIGN KEY (user_id) REFERENCES directory_user (id) ON DELETE CASCADE
);

CREATE INDEX idx_project_admin_user ON project_admin (user_id);

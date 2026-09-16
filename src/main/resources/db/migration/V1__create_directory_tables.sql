-- Initial schema for cert-alert.
-- Portable SQL so the same migration runs on H2 (dev/test) and PostgreSQL.
--
-- Two primary object types are scraped from the directory, following the IC FSD schema:
-- IC Persons (icOrgPerson) and IC Non-Person Entities (icOrgServer). Each holds cached
-- certificate details, plus denormalised roll-up columns the search tables filter on.
--
-- Ids come from sequences rather than identity columns so Hibernate can batch inserts;
-- the increments match the allocation sizes the entities declare.

CREATE SEQUENCE directory_entry_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE cached_certificate_seq START WITH 1 INCREMENT BY 100;
CREATE SEQUENCE sync_run_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE directory_user (
    id                     BIGINT PRIMARY KEY,
    dn                     VARCHAR(512) NOT NULL,
    uid                    VARCHAR(255),
    common_name            VARCHAR(255),
    display_name           VARCHAR(255),
    preferred_name         VARCHAR(255),
    given_name             VARCHAR(255),
    surname                VARCHAR(255),
    -- Primary address, chosen from the network addresses by configured precedence.
    email                  VARCHAR(320),
    mail                   VARCHAR(320),
    ic_email               VARCHAR(320),
    internet_email         VARCHAR(320),
    niprnet_email          VARCHAR(320),
    siprnet_email          VARCHAR(320),
    telephone_number       VARCHAR(64),
    title                  VARCHAR(255),
    employee_type          VARCHAR(128),
    rank_title             VARCHAR(128),
    country_of_affiliation VARCHAR(128),
    duty_organization      VARCHAR(255),
    admin_organization     VARCHAR(255),
    is_ic_member           BOOLEAN,
    ic_networks            VARCHAR(500),
    resource_security_mark VARCHAR(500),
    organization           VARCHAR(255),
    organizational_unit    VARCHAR(255),
    certificate_count      INTEGER NOT NULL DEFAULT 0,
    certificate_status     VARCHAR(32) NOT NULL DEFAULT 'NONE',
    earliest_expiry        TIMESTAMP WITH TIME ZONE,
    latest_expiry          TIMESTAMP WITH TIME ZONE,
    first_seen_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    last_synced_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    version                BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_directory_user_dn UNIQUE (dn)
);

CREATE INDEX idx_directory_user_email ON directory_user (email);
CREATE INDEX idx_directory_user_uid ON directory_user (uid);
CREATE INDEX idx_directory_user_cert_status ON directory_user (certificate_status);
CREATE INDEX idx_directory_user_earliest_expiry ON directory_user (earliest_expiry);
CREATE INDEX idx_directory_user_last_synced ON directory_user (last_synced_at);

-- Every value a server's serverPOC could use to name this person: each of their
-- addresses, and each form of their name. This is the join between the two object types.
CREATE TABLE directory_user_identifier (
    user_id    BIGINT NOT NULL,
    identifier VARCHAR(320) NOT NULL,
    CONSTRAINT fk_user_identifier_user FOREIGN KEY (user_id)
        REFERENCES directory_user (id) ON DELETE CASCADE
);

CREATE INDEX idx_user_identifier_value ON directory_user_identifier (identifier);

CREATE TABLE directory_server (
    id                     BIGINT PRIMARY KEY,
    dn                     VARCHAR(512) NOT NULL,
    common_name            VARCHAR(255),
    uid                    VARCHAR(255),
    given_name             VARCHAR(255),
    description            VARCHAR(1000),
    server_url             VARCHAR(500),
    ic_server_address      VARCHAR(128),
    ato_status             VARCHAR(128),
    life_cycle_status      VARCHAR(128),
    employee_type          VARCHAR(128),
    country_of_affiliation VARCHAR(128),
    duty_organization      VARCHAR(255),
    admin_organization     VARCHAR(255),
    is_ic_member           BOOLEAN,
    ic_networks            VARCHAR(500),
    resource_security_mark VARCHAR(500),
    server_poc_display     VARCHAR(2000),
    organization           VARCHAR(255),
    organizational_unit    VARCHAR(255),
    certificate_count      INTEGER NOT NULL DEFAULT 0,
    certificate_status     VARCHAR(32) NOT NULL DEFAULT 'NONE',
    earliest_expiry        TIMESTAMP WITH TIME ZONE,
    latest_expiry          TIMESTAMP WITH TIME ZONE,
    first_seen_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    last_synced_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    version                BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_directory_server_dn UNIQUE (dn)
);

CREATE INDEX idx_directory_server_cn ON directory_server (common_name);
CREATE INDEX idx_directory_server_url ON directory_server (server_url);
CREATE INDEX idx_directory_server_cert_status ON directory_server (certificate_status);
CREATE INDEX idx_directory_server_earliest_expiry ON directory_server (earliest_expiry);
CREATE INDEX idx_directory_server_last_synced ON directory_server (last_synced_at);

-- serverPOC is single-valued in the specification, but held as a set: it carries a name
-- rather than a key, and directories deviate.
CREATE TABLE directory_server_poc (
    server_id BIGINT NOT NULL,
    poc_value VARCHAR(320) NOT NULL,
    CONSTRAINT fk_server_poc_server FOREIGN KEY (server_id)
        REFERENCES directory_server (id) ON DELETE CASCADE
);

CREATE INDEX idx_server_poc_value ON directory_server_poc (poc_value);

CREATE TABLE cached_certificate (
    id                        BIGINT PRIMARY KEY,
    user_id                   BIGINT,
    server_id                 BIGINT,
    sha256_fingerprint        VARCHAR(64) NOT NULL,
    serial_number             VARCHAR(100),
    subject_dn                VARCHAR(1000),
    issuer_dn                 VARCHAR(1000),
    not_before                TIMESTAMP WITH TIME ZONE,
    not_after                 TIMESTAMP WITH TIME ZONE,
    signature_algorithm       VARCHAR(100),
    key_algorithm             VARCHAR(50),
    key_size                  INTEGER,
    subject_alternative_names VARCHAR(2000),
    status                    VARCHAR(32) NOT NULL DEFAULT 'VALID',
    cached_at                 TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_cached_certificate_user FOREIGN KEY (user_id)
        REFERENCES directory_user (id) ON DELETE CASCADE,
    CONSTRAINT fk_cached_certificate_server FOREIGN KEY (server_id)
        REFERENCES directory_server (id) ON DELETE CASCADE,
    -- A certificate hangs off exactly one owner, never both and never neither.
    CONSTRAINT ck_cached_certificate_owner CHECK (
        (user_id IS NOT NULL AND server_id IS NULL)
        OR (user_id IS NULL AND server_id IS NOT NULL)
    )
);

CREATE INDEX idx_cached_certificate_user ON cached_certificate (user_id);
CREATE INDEX idx_cached_certificate_server ON cached_certificate (server_id);
CREATE INDEX idx_cached_certificate_not_after ON cached_certificate (not_after);
CREATE INDEX idx_cached_certificate_fingerprint ON cached_certificate (sha256_fingerprint);
-- Drives the expiry re-evaluation job, which walks certificates that are not yet expired
-- and whose notAfter has come within the warning window.
CREATE INDEX idx_cached_certificate_status_expiry ON cached_certificate (status, not_after);

-- One row per scheduled run, so operators can see what the last sweep did and the prune
-- job can tell a complete run from one that died halfway through.
CREATE TABLE sync_run (
    id                   BIGINT PRIMARY KEY,
    job                  VARCHAR(32) NOT NULL,
    status               VARCHAR(32) NOT NULL,
    started_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at          TIMESTAMP WITH TIME ZONE,
    entries_seen         INTEGER NOT NULL DEFAULT 0,
    entries_created      INTEGER NOT NULL DEFAULT 0,
    certificates_cached  INTEGER NOT NULL DEFAULT 0,
    certificates_removed INTEGER NOT NULL DEFAULT 0,
    alerts_raised        INTEGER NOT NULL DEFAULT 0,
    entries_pruned       INTEGER NOT NULL DEFAULT 0,
    errors               INTEGER NOT NULL DEFAULT 0,
    message              VARCHAR(2000)
);

CREATE INDEX idx_sync_run_job_started ON sync_run (job, started_at);

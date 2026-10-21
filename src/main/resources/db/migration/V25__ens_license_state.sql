-- The ENS license, this installation's id and the license revisions seen, for the license
-- library's JdbcLicenseStore (ens-license 1.2.0): in the database rather than on the license
-- volume, so every replica shares them.
--
-- The library's own DDL for PostgreSQL (com/enssolutions/license/schema/postgresql.sql),
-- unchanged. H2 reads TEXT as a character type, which serves the license's few kilobytes.
-- The first start after this migration adopts the installation id, revisions and license
-- from the license volume, if one is mounted and the database holds no id yet.

CREATE TABLE ens_license_installation (
    product          VARCHAR(64)  NOT NULL PRIMARY KEY,
    installation_id  VARCHAR(32)  NOT NULL,
    created_at       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE ens_license_file (
    product          VARCHAR(64)  NOT NULL PRIMARY KEY,
    license_text     TEXT         NOT NULL,
    license_version  BIGINT       NOT NULL,
    updated_at       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE TABLE ens_license_revision (
    product          VARCHAR(64)  NOT NULL,
    license_id       VARCHAR(128) NOT NULL,
    revision         INTEGER      NOT NULL,
    updated_at       TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    PRIMARY KEY (product, license_id)
);

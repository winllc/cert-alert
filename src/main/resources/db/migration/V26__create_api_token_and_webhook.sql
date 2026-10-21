-- The public API's tokens, and the webhooks that tell other systems what happened.
--
-- api_token: a bearer token for /api/v1/**, created by an administrator and shown to them
-- once. Only the token's SHA-256 is kept. scopes is a comma-separated list
-- (certificates:read, jobs:run, admin ...). creator_roles is the most the token may ever
-- do: the roles its creator held when it was made, narrowed whenever they sign in again
-- and, for a creator whose roles come from the directory, checked again as it is used.
-- A token past expires_at, or revoked, authenticates nothing.
--
-- webhook_endpoint: where expiry alerts and job results are posted, signed with
-- HMAC-SHA256 under secret. The secret is shown to the administrator once, when the
-- endpoint is created; it has to be kept in a form that can sign, so it is stored here, and
-- encrypted with cert-alert.webhooks.secret-key where that is set (secret_encrypted).
--
-- webhook_delivery: an outbox, like outbound_email. A delivery is written in the same
-- transaction as whatever caused it, and posted after that commits, by the application,
-- outside any transaction - retried further apart each time until it goes or is old
-- enough to give up on. The row is the delivery log the integrations page shows.

CREATE SEQUENCE api_token_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE api_token (
    id              BIGINT PRIMARY KEY,
    name            VARCHAR(120) NOT NULL,
    token_hash      VARCHAR(64) NOT NULL,
    prefix          VARCHAR(16) NOT NULL,
    scopes          VARCHAR(500) NOT NULL,
    created_by      VARCHAR(320) NOT NULL,
    creator_dn      VARCHAR(1000),
    creator_method  VARCHAR(16),
    creator_roles   VARCHAR(200) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE,
    last_used_at    TIMESTAMP WITH TIME ZONE,
    last_used_from  VARCHAR(64),
    revoked_at      TIMESTAMP WITH TIME ZONE,
    revoked_by      VARCHAR(320),
    CONSTRAINT uk_api_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_api_token_creator ON api_token (creator_dn);
CREATE INDEX idx_api_token_created_by ON api_token (created_by);

CREATE SEQUENCE webhook_endpoint_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE webhook_endpoint (
    id                BIGINT PRIMARY KEY,
    name              VARCHAR(120) NOT NULL,
    url               VARCHAR(2000) NOT NULL,
    format            VARCHAR(16) NOT NULL,
    events            VARCHAR(500) NOT NULL,
    secret            VARCHAR(500) NOT NULL,
    secret_encrypted  BOOLEAN NOT NULL,
    secret_prefix     VARCHAR(16) NOT NULL,
    enabled           BOOLEAN NOT NULL,
    created_by        VARCHAR(320) NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE SEQUENCE webhook_delivery_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE webhook_delivery (
    id               BIGINT PRIMARY KEY,
    endpoint_id      BIGINT NOT NULL,
    event_id         VARCHAR(36) NOT NULL,
    event_type       VARCHAR(64) NOT NULL,
    state            VARCHAR(16) NOT NULL,
    payload          VARCHAR(1000000) NOT NULL,
    attempts         INTEGER NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    next_attempt_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    delivered_at     TIMESTAMP WITH TIME ZONE,
    last_status      INTEGER,
    last_error       VARCHAR(1000),
    CONSTRAINT fk_webhook_delivery_endpoint FOREIGN KEY (endpoint_id)
        REFERENCES webhook_endpoint (id) ON DELETE CASCADE
);

-- What is due, oldest first.
CREATE INDEX idx_webhook_delivery_due ON webhook_delivery (state, next_attempt_at);
-- An endpoint's log, newest first.
CREATE INDEX idx_webhook_delivery_endpoint ON webhook_delivery (endpoint_id, created_at);
CREATE INDEX idx_webhook_delivery_created ON webhook_delivery (created_at);

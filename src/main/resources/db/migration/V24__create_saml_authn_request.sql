-- SAML authentication requests this application has sent and not yet had an answer to,
-- by the RelayState that comes back with the answer.
--
-- Not in the session, as Spring Security keeps them by default, for two reasons. The
-- identity provider answers with a cross-site POST, which a SameSite=Lax session cookie is
-- not sent with, so the session that sent the request is not there to find it in. And with
-- more than one replica the answer may reach a replica other than the one that asked.
--
-- Each is used once - deleted as the answer is read - and is good for ten minutes, which is
-- what makes a response that is replayed, or that answers nothing this application asked,
-- unacceptable: every response must name (InResponseTo) a request still waiting here.

CREATE TABLE saml_authn_request (
    relay_state VARCHAR(128) PRIMARY KEY,
    request     BYTEA        NOT NULL,
    consented   BOOLEAN      NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_saml_authn_request_created ON saml_authn_request (created_at);

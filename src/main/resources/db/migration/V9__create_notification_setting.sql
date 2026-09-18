-- How far ahead the expiry round-up looks, set from the UI rather than the configuration.
--
-- The number of days before expiry that an email goes out is the one thing here that gets
-- argued about after the deployment: thirty is too late for a certificate whose renewal
-- needs a change request, and too early for a service that reissues weekly. Editing a YAML
-- file and restarting is a poor way to settle it, so it lives here instead.
--
-- One row, with a fixed id. There is one answer for the deployment, not one per person: a
-- round-up is written to a point of contact, who may never sign in here at all.

CREATE TABLE notification_setting (
    id         BIGINT PRIMARY KEY,
    lead_days  INTEGER NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by VARCHAR(320),
    CONSTRAINT ck_notification_setting_lead_days CHECK (lead_days BETWEEN 1 AND 365)
);

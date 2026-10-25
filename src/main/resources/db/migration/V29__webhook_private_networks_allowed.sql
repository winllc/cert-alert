-- Private addresses are now refused for webhooks by default (cert-alert.webhooks.block-private-
-- networks unset). Every webhook that exists at this upgrade was saved when they were allowed,
-- so it keeps the address it has - with a warning - until its URL is changed: an upgrade does
-- not silently stop a webhook to a receiver inside the network. Additive only (expand): a
-- replica still on the previous release ignores the column, and its new rows get FALSE.

ALTER TABLE webhook_endpoint ADD COLUMN private_networks_allowed BOOLEAN DEFAULT FALSE NOT NULL;
UPDATE webhook_endpoint SET private_networks_allowed = TRUE;

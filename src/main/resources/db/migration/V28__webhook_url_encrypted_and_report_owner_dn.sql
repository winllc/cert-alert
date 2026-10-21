-- A webhook's URL can be a secret in itself - a Slack or Teams incoming webhook is - so it is
-- kept encrypted beside its signing secret where cert-alert.webhooks.secret-key is set, which
-- takes more room than the URL. And a scheduled report records whose authority it runs on,
-- so each run can check that person is still an administrator in the directory.

ALTER TABLE webhook_endpoint ALTER COLUMN url SET DATA TYPE VARCHAR(4000);
ALTER TABLE webhook_endpoint ADD COLUMN url_encrypted BOOLEAN DEFAULT FALSE NOT NULL;

ALTER TABLE scheduled_report ADD COLUMN owner_dn VARCHAR(1000);

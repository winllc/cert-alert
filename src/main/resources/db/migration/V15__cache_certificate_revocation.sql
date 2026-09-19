-- Whether an authority has revoked a certificate, and where it says to ask.
--
-- Expiry is arithmetic on a date the certificate carries; revocation is somebody else's
-- decision published somewhere else, and a revoked certificate looks exactly like a good
-- one until that somewhere else is asked. A key reported stolen last March is still valid
-- until 2028 as far as every other column here is concerned.
--
-- The two endpoint columns are read from the certificate when it is parsed, because they
-- are carried by the certificate and by nothing else. Without them the check knows the
-- serial number and not the responder that would recognise it - and finding that out per
-- certificate would mean reading the directory again for every one of them.
--
-- Left as NOT_CHECKED for what is already cached, which is the honest value: nothing has
-- been asked about them yet. A sweep re-parses and fills in the endpoints; the revocation
-- job then has somewhere to ask.

ALTER TABLE cached_certificate ADD COLUMN crl_urls VARCHAR(1000);
ALTER TABLE cached_certificate ADD COLUMN ocsp_url VARCHAR(500);
ALTER TABLE cached_certificate ADD COLUMN authority_key_id VARCHAR(128);

ALTER TABLE cached_certificate ADD COLUMN revocation_status VARCHAR(16) DEFAULT 'NOT_CHECKED' NOT NULL;
ALTER TABLE cached_certificate ADD COLUMN revocation_method VARCHAR(8);
ALTER TABLE cached_certificate ADD COLUMN revocation_checked_at TIMESTAMP;
ALTER TABLE cached_certificate ADD COLUMN revoked_at TIMESTAMP;
ALTER TABLE cached_certificate ADD COLUMN revocation_reason VARCHAR(64);
ALTER TABLE cached_certificate ADD COLUMN revocation_detail VARCHAR(500);

-- The question the metrics page asks, and the one the job asks to find what to re-check.
CREATE INDEX idx_cached_certificate_revocation ON cached_certificate (revocation_status);

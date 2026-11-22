-- When a certificate was first found revoked. The certificate cleanup's revoked-after is
-- measured from this: revocation_checked_at moves on with every nightly check, so a revoked
-- certificate that is still current never looked old enough to remove.
--
-- What is revoked already takes its last check as the nearest thing known, which is no
-- earlier than the truth: cleanup waits a little longer for those, never less.

ALTER TABLE cached_certificate ADD COLUMN revocation_found_at TIMESTAMP;
UPDATE cached_certificate SET revocation_found_at = revocation_checked_at WHERE revocation_status = 'REVOKED';

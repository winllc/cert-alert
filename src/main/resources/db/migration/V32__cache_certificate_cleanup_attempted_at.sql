-- When the certificate cleanup last tried to remove a certificate and could not: the entry
-- gone from the directory, or the modify refused. The cleanup reads its candidates oldest
-- first up to max-per-run, and such rows stay cached, so once there were max-per-run of them
-- every run chose the same ones, removed nothing and never reached the rest. Ordered by this
-- first, nulls first, they wait behind what has not been tried. Null for everything existing.

ALTER TABLE cached_certificate ADD COLUMN cleanup_attempted_at TIMESTAMP;

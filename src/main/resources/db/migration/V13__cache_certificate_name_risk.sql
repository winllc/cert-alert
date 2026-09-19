-- What a certificate is good for, and what is worrying about it.
--
-- The names are already cached; what was missing is how many there are and what shape they
-- are in. A wildcard covers hosts that do not exist yet, a name with no domain means
-- something different on every network, and forty names means one key for forty things.
-- None of them is a fault in itself, so they are flags to look at rather than alerts, and
-- they live on the row so the tables can filter and the metrics can count.

ALTER TABLE cached_certificate ADD COLUMN san_count INTEGER;
ALTER TABLE cached_certificate ADD COLUMN risk_flags VARCHAR(200);

CREATE INDEX idx_cached_certificate_risk ON cached_certificate (risk_flags);

-- Best effort for what is already cached, from the names as they were stored. A sweep
-- re-parses from the certificate itself and corrects both columns; until then this is
-- enough to find the wildcards, which is what somebody looks for first.
--
-- The stored list is truncated with an ellipsis when it is long, so a truncated one is
-- certainly over any sane threshold - that is what makes the count trustworthy enough to
-- flag on without re-reading the directory.
UPDATE cached_certificate
SET san_count = CASE
        WHEN subject_alternative_names IS NULL OR subject_alternative_names = '' THEN 0
        ELSE LENGTH(subject_alternative_names) - LENGTH(REPLACE(subject_alternative_names, ',', '')) + 1
    END
WHERE san_count IS NULL;

UPDATE cached_certificate
SET risk_flags = TRIM(BOTH ',' FROM
        CASE WHEN subject_alternative_names LIKE '%*.%' THEN 'WILDCARD,' ELSE '' END
        || CASE WHEN san_count > 20 OR subject_alternative_names LIKE '%...' THEN 'MANY_NAMES,' ELSE '' END)
WHERE subject_alternative_names IS NOT NULL
  AND (subject_alternative_names LIKE '%*.%' OR san_count > 20 OR subject_alternative_names LIKE '%...');

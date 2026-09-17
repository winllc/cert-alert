-- The digest a certificate was signed over, on its own.
--
-- signature_algorithm already holds it, spelled as the provider names the pair:
-- "SHA256withRSA". A report asking which entries are still signed with SHA-1 should not
-- have to pattern-match that, and pattern-matching would miss RSASSA-PSS entirely - there
-- the name says nothing about the digest, which is in the signature parameters.

ALTER TABLE cached_certificate ADD COLUMN hash_algorithm VARCHAR(32);

-- Backfill what can be read off the existing names. Anything else stays null until the
-- certificate is next cached, at which point it is derived from the certificate itself.
UPDATE cached_certificate SET hash_algorithm =
    CASE
        WHEN UPPER(signature_algorithm) LIKE 'SHA1WITH%'    THEN 'SHA-1'
        WHEN UPPER(signature_algorithm) LIKE 'SHA224WITH%'  THEN 'SHA-224'
        WHEN UPPER(signature_algorithm) LIKE 'SHA256WITH%'  THEN 'SHA-256'
        WHEN UPPER(signature_algorithm) LIKE 'SHA384WITH%'  THEN 'SHA-384'
        WHEN UPPER(signature_algorithm) LIKE 'SHA512WITH%'  THEN 'SHA-512'
        WHEN UPPER(signature_algorithm) LIKE 'SHA3-224WITH%' THEN 'SHA3-224'
        WHEN UPPER(signature_algorithm) LIKE 'SHA3-256WITH%' THEN 'SHA3-256'
        WHEN UPPER(signature_algorithm) LIKE 'SHA3-384WITH%' THEN 'SHA3-384'
        WHEN UPPER(signature_algorithm) LIKE 'SHA3-512WITH%' THEN 'SHA3-512'
        WHEN UPPER(signature_algorithm) LIKE 'MD5WITH%'     THEN 'MD5'
        WHEN UPPER(signature_algorithm) LIKE 'MD2WITH%'     THEN 'MD2'
        ELSE NULL
    END
WHERE signature_algorithm IS NOT NULL;

-- What a report on the directory's cryptography would filter by: the weak digests, and the
-- undersized keys. Both are small, and both exist because these columns are kept for
-- exactly that purpose.
CREATE INDEX idx_cached_certificate_hash_algorithm ON cached_certificate (hash_algorithm);
CREATE INDEX idx_cached_certificate_key ON cached_certificate (key_algorithm, key_size);

-- Which half of a person's credentials a certificate is.
--
-- A person in this directory holds two certificates, not one: a signing certificate and a
-- key encipherment certificate, issued at the same time. The key usage extension is the
-- only thing that says which is which, so without it "the most recently issued certificate"
-- is a question with two answers and no way to tell them apart.
--
-- Left null for what is already cached; a sweep re-parses from the certificate and fills it
-- in, and until then a certificate simply reads as unspecified rather than as the wrong
-- half of a pair. Nothing can be inferred from what is already stored - the extension is
-- not one of the columns.

ALTER TABLE cached_certificate ADD COLUMN key_usage VARCHAR(200);

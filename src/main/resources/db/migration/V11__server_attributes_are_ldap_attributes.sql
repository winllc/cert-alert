-- The managed attributes are the directory's own attributes, not this application's.
--
-- V10 kept the values here, which was wrong: a server's attributes belong to the entry in
-- the directory, and an edit has to reach the directory or it is an edit to a copy nobody
-- else can see. The definition now names the LDAP attribute it manages; the values are read
-- from the entry when a page asks and written straight back when somebody changes one, so
-- there is nothing left to keep in step.

ALTER TABLE server_attribute_definition ADD COLUMN ldap_attribute VARCHAR(120);

-- Anything defined under V10 was named after what it holds, which is the best guess there
-- is at the attribute it meant; a deployment with rows here will want to check them.
UPDATE server_attribute_definition SET ldap_attribute = name WHERE ldap_attribute IS NULL;

ALTER TABLE server_attribute_definition ALTER COLUMN ldap_attribute SET NOT NULL;

ALTER TABLE server_attribute_definition
    ADD CONSTRAINT uk_server_attribute_definition_ldap UNIQUE (ldap_attribute);

DROP TABLE server_attribute_value;
DROP SEQUENCE server_attribute_value_seq;

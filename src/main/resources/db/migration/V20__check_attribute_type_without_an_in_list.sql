-- The same rule as V10's - an attribute is TEXT, CHOICE or BOOLEAN - written so that H2 can
-- go on checking it.
--
-- H2 2.4 compiles a CHECK on a list of constants (type IN (...), or the ORs of equalities it
-- folds into one) into a set that holds on to the connection that created the constraint.
-- Once that connection closes - Flyway's, when the pool retires it, about half an hour
-- after the first start against a new H2 database - every insert into this table fails with
-- "Check constraint invalid ... The database has been closed", until a restart reads the
-- schema back in afresh. A CASE is evaluated with whichever connection is inserting.
-- PostgreSQL is indifferent to the form; for it this changes nothing.
ALTER TABLE server_attribute_definition DROP CONSTRAINT ck_server_attribute_definition_type;
ALTER TABLE server_attribute_definition ADD CONSTRAINT ck_server_attribute_definition_type CHECK (
    CASE type WHEN 'TEXT' THEN TRUE WHEN 'CHOICE' THEN TRUE WHEN 'BOOLEAN' THEN TRUE ELSE FALSE END
);

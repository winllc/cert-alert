-- The part of the duty organization an entry belongs to.
--
-- A duty organization is an agency; a duty sub-organization is the office inside it, which
-- is the level at which somebody actually answers for a certificate. Reporting that stops
-- at the agency says "Example Agency holds 40,000 certificates", which is true and of no
-- use to anybody.
--
-- Null until a sweep re-reads the entries, which is where every other directory attribute
-- here comes from.

ALTER TABLE directory_user ADD COLUMN duty_sub_organization VARCHAR(255);
ALTER TABLE directory_server ADD COLUMN duty_sub_organization VARCHAR(255);

-- The reporting groups by it, which on a directory of this size is a scan without these.
CREATE INDEX idx_directory_user_duty_sub_org ON directory_user (duty_sub_organization);
CREATE INDEX idx_directory_server_duty_sub_org ON directory_server (duty_sub_organization);

-- Attributes this application keeps about a server, on top of what the directory publishes.
--
-- The FSD schema is somebody else's and fixed; the things a team actually wants recorded
-- against a server - which environment it is, whether it is in scope for an audit, who
-- funds it - are not in it and never will be. So an administrator defines the attributes
-- here and they are filled in per server, rather than every deployment carrying a patch.
--
-- A definition is free text, a choice from a list, or a boolean. Text and choice hold one
-- value or several; a boolean is one by definition, which the check constraint says out
-- loud rather than leaving to the application.

CREATE SEQUENCE server_attribute_definition_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE server_attribute_value_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE server_attribute_definition (
    id            BIGINT PRIMARY KEY,
    name          VARCHAR(120) NOT NULL,
    description   VARCHAR(500),
    type          VARCHAR(16) NOT NULL,
    multi_valued  BOOLEAN NOT NULL DEFAULT FALSE,
    display_order INTEGER NOT NULL DEFAULT 0,
    created_by    VARCHAR(320),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_by    VARCHAR(320),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_server_attribute_definition_name UNIQUE (name),
    CONSTRAINT ck_server_attribute_definition_type CHECK (type IN ('TEXT', 'CHOICE', 'BOOLEAN')),
    CONSTRAINT ck_server_attribute_definition_boolean CHECK (type <> 'BOOLEAN' OR multi_valued = FALSE)
);

-- What a choice attribute may be set to, in the order the drop-down shows them.
-- "value" and "position" are reserved on H2, so the columns say what they hold instead.
CREATE TABLE server_attribute_option (
    definition_id BIGINT NOT NULL,
    sort_order    INTEGER NOT NULL,
    option_value  VARCHAR(200) NOT NULL,
    PRIMARY KEY (definition_id, sort_order),
    CONSTRAINT fk_server_attribute_option_definition FOREIGN KEY (definition_id)
        REFERENCES server_attribute_definition (id) ON DELETE CASCADE
);

CREATE TABLE server_attribute_value (
    id              BIGINT PRIMARY KEY,
    definition_id   BIGINT NOT NULL,
    server_id       BIGINT NOT NULL,
    attribute_value VARCHAR(1000) NOT NULL,
    sort_order      INTEGER NOT NULL DEFAULT 0,
    updated_by      VARCHAR(320),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Both sides cascade. Retiring an attribute takes the values with it: they mean nothing
    -- without the definition that named them. A server pruned from the directory takes its
    -- own values, like every other row that hangs off an entry.
    CONSTRAINT fk_server_attribute_value_definition FOREIGN KEY (definition_id)
        REFERENCES server_attribute_definition (id) ON DELETE CASCADE,
    CONSTRAINT fk_server_attribute_value_server FOREIGN KEY (server_id)
        REFERENCES directory_server (id) ON DELETE CASCADE,
    CONSTRAINT uk_server_attribute_value UNIQUE (definition_id, server_id, attribute_value)
);

CREATE INDEX idx_server_attribute_value_server ON server_attribute_value (server_id);
CREATE INDEX idx_server_attribute_value_definition ON server_attribute_value (definition_id);

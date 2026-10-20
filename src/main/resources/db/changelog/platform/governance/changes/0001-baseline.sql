--liquibase formatted sql

-- The platform/governance module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:platform-governance-0001-baseline splitStatements:true
CREATE TABLE classification_finding (
    first_seen_at timestamp with time zone NOT NULL,
    last_seen_at timestamp with time zone NOT NULL,
    hit_count bigint NOT NULL,
    destination text NOT NULL,
    location text NOT NULL,
    field_path text NOT NULL,
    data_class text NOT NULL,
    status text DEFAULT 'OPEN'::text NOT NULL,
    id uuid DEFAULT uuidv7() NOT NULL,
    CONSTRAINT ck_classification_finding_status CHECK ((status = ANY (ARRAY['OPEN'::text, 'CONFIRMED'::text, 'DISMISSED'::text])))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05');

CREATE TABLE governance_policy (
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    version integer NOT NULL,
    id smallint DEFAULT 1 NOT NULL,
    CONSTRAINT ck_governance_policy_single CHECK ((id = 1))
);

CREATE TABLE governance_rule (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    destination_pattern text,
    target text NOT NULL,
    selector text NOT NULL,
    data_class text NOT NULL,
    action text,
    id uuid DEFAULT uuidv7() NOT NULL,
    builtin boolean DEFAULT false NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    is_exception boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_governance_rule_action CHECK (((action IS NULL) OR (action = ANY (ARRAY['DROP'::text, 'PARTIAL'::text, 'REDACT'::text, 'CLEAR'::text])))),
    CONSTRAINT ck_governance_rule_class CHECK ((data_class = ANY (ARRAY['CREDENTIAL'::text, 'PAN'::text, 'IBAN'::text, 'EMAIL'::text, 'PHONE'::text, 'NATIONAL_ID'::text, 'PERSONAL'::text]))),
    CONSTRAINT ck_governance_rule_target CHECK ((target = ANY (ARRAY['HEADER'::text, 'PROPERTY'::text, 'BODY_PATH'::text])))
);

ALTER TABLE ONLY classification_finding
    ADD CONSTRAINT pk_classification_finding PRIMARY KEY (id);

ALTER TABLE ONLY governance_policy
    ADD CONSTRAINT pk_governance_policy PRIMARY KEY (id);

ALTER TABLE ONLY governance_rule
    ADD CONSTRAINT pk_governance_rule PRIMARY KEY (id);

ALTER TABLE ONLY classification_finding
    ADD CONSTRAINT uq_classification_finding UNIQUE (destination, location, field_path, data_class);

INSERT INTO governance_policy (version) VALUES (1);

-- Built-in credential rules (data-governance spec). Fixed ids so they are recognisable everywhere.
INSERT INTO governance_rule (target, selector, data_class, id, builtin) VALUES
    ('PROPERTY', 'authorization',       'CREDENTIAL', '00000000-0000-0000-0075-000000000001', TRUE),
    ('PROPERTY', 'proxy-authorization', 'CREDENTIAL', '00000000-0000-0000-0075-000000000002', TRUE),
    ('PROPERTY', 'cookie',              'CREDENTIAL', '00000000-0000-0000-0075-000000000003', TRUE),
    ('PROPERTY', 'set-cookie',          'CREDENTIAL', '00000000-0000-0000-0075-000000000004', TRUE),
    ('PROPERTY', '*password*',          'CREDENTIAL', '00000000-0000-0000-0075-000000000005', TRUE),
    ('PROPERTY', '*secret*',            'CREDENTIAL', '00000000-0000-0000-0075-000000000006', TRUE),
    ('PROPERTY', '*token*',             'CREDENTIAL', '00000000-0000-0000-0075-000000000007', TRUE),
    ('PROPERTY', '*api-key*',           'CREDENTIAL', '00000000-0000-0000-0075-000000000008', TRUE),
    ('PROPERTY', '*api_key*',           'CREDENTIAL', '00000000-0000-0000-0075-000000000009', TRUE);
--rollback DROP TABLE IF EXISTS governance_rule CASCADE;
--rollback DROP TABLE IF EXISTS governance_policy CASCADE;
--rollback DROP TABLE IF EXISTS classification_finding CASCADE;

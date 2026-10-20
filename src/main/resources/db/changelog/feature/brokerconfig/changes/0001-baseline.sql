--liquibase formatted sql

-- The feature/brokerconfig module's schema (ADR-0192): the final shape of its tables, written in one file. The
-- module configures Artemis brokers only, so its tables carry the system's prefix, artemis_.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:feature-brokerconfig-0001-baseline splitStatements:true
CREATE TABLE artemis_config_apply (
    started_at timestamp with time zone DEFAULT now() NOT NULL,
    finished_at timestamp with time zone,
    id bigint NOT NULL,
    revision_id bigint NOT NULL,
    audit_event_id bigint,
    plan jsonb NOT NULL,
    outcome_detail jsonb,
    outcome text NOT NULL,
    summary text,
    actor text NOT NULL,
    cluster_id uuid NOT NULL,
    canary_node_id uuid,
    dry_run boolean NOT NULL,
    CONSTRAINT ck_artemis_config_apply_outcome CHECK ((outcome = ANY (ARRAY['DRY_RUN'::text, 'APPLIED'::text, 'HALTED'::text, 'FAILED'::text])))
);

CREATE TABLE artemis_config_declaration (
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    current_revision_id bigint NOT NULL,
    apply_mode text DEFAULT 'RAHDAR_MANAGED'::text NOT NULL,
    undeclared_exclusions jsonb DEFAULT '[]'::jsonb NOT NULL,
    cluster_id uuid NOT NULL,
    report_undeclared boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_artemis_config_declaration_mode CHECK ((apply_mode = ANY (ARRAY['RAHDAR_MANAGED'::text, 'CONFIG_MANAGED'::text])))
);

CREATE TABLE artemis_config_node_state (
    evaluated_at timestamp with time zone DEFAULT now() NOT NULL,
    basis_ref bigint,
    verified_revision integer,
    findings jsonb DEFAULT '[]'::jsonb NOT NULL,
    state text NOT NULL,
    detail text,
    basis text,
    cluster_id uuid NOT NULL,
    node_id uuid NOT NULL,
    CONSTRAINT ck_artemis_config_node_state_basis CHECK (((basis IS NULL) OR (basis = ANY (ARRAY['VERIFIED_APPLY'::text, 'ADOPTED'::text, 'OBSERVED_MATCH'::text])))),
    CONSTRAINT ck_artemis_config_node_state_state CHECK ((state = ANY (ARRAY['IN_SYNC'::text, 'DRIFTED'::text, 'NOT_EVALUATED'::text, 'UNREACHABLE'::text])))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05');

CREATE TABLE artemis_config_owned_item (
    applied_at timestamp with time zone DEFAULT now() NOT NULL,
    revision_id bigint NOT NULL,
    kind text NOT NULL,
    item_key text NOT NULL,
    cluster_id uuid NOT NULL,
    CONSTRAINT ck_artemis_config_owned_item_kind CHECK ((kind = ANY (ARRAY['ADDRESS_SETTING'::text, 'SECURITY_SETTING'::text, 'DIVERT'::text, 'BRIDGE'::text])))
);

CREATE TABLE artemis_config_revision (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    id bigint NOT NULL,
    revision integer NOT NULL,
    document jsonb NOT NULL,
    source text NOT NULL,
    note text,
    created_by text NOT NULL,
    cluster_id uuid NOT NULL,
    CONSTRAINT ck_artemis_config_revision_source CHECK ((source = ANY (ARRAY['EDIT'::text, 'IMPORT_XML'::text, 'ADOPT'::text, 'MCP'::text, 'RECOMMENDED'::text])))
);

ALTER TABLE artemis_config_apply ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME artemis_config_apply_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE artemis_config_revision ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME artemis_config_revision_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY artemis_config_apply
    ADD CONSTRAINT pk_artemis_config_apply PRIMARY KEY (id);

ALTER TABLE ONLY artemis_config_declaration
    ADD CONSTRAINT pk_artemis_config_declaration PRIMARY KEY (cluster_id);

ALTER TABLE ONLY artemis_config_node_state
    ADD CONSTRAINT pk_artemis_config_node_state PRIMARY KEY (cluster_id, node_id);

ALTER TABLE ONLY artemis_config_owned_item
    ADD CONSTRAINT pk_artemis_config_owned_item PRIMARY KEY (cluster_id, kind, item_key);

ALTER TABLE ONLY artemis_config_revision
    ADD CONSTRAINT pk_artemis_config_revision PRIMARY KEY (id);

ALTER TABLE ONLY artemis_config_revision
    ADD CONSTRAINT uq_artemis_config_revision UNIQUE (cluster_id, revision);

CREATE INDEX ix_artemis_config_apply_cluster ON artemis_config_apply USING btree (cluster_id, started_at DESC);

CREATE INDEX ix_artemis_config_apply_revision_id ON artemis_config_apply USING btree (revision_id);

CREATE INDEX ix_artemis_config_declaration_current_revision_id ON artemis_config_declaration USING btree (current_revision_id);

CREATE INDEX ix_artemis_config_node_state_node_id ON artemis_config_node_state USING btree (node_id);

ALTER TABLE ONLY artemis_config_apply
    ADD CONSTRAINT fk_artemis_config_apply_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY artemis_config_apply
    ADD CONSTRAINT fk_artemis_config_apply_revision FOREIGN KEY (revision_id) REFERENCES artemis_config_revision(id);

ALTER TABLE ONLY artemis_config_declaration
    ADD CONSTRAINT fk_artemis_config_declaration_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY artemis_config_declaration
    ADD CONSTRAINT fk_artemis_config_declaration_revision FOREIGN KEY (current_revision_id) REFERENCES artemis_config_revision(id);

ALTER TABLE ONLY artemis_config_node_state
    ADD CONSTRAINT fk_artemis_config_node_state_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY artemis_config_node_state
    ADD CONSTRAINT fk_artemis_config_node_state_node FOREIGN KEY (node_id) REFERENCES broker_node(id) ON DELETE CASCADE;

ALTER TABLE ONLY artemis_config_owned_item
    ADD CONSTRAINT fk_artemis_config_owned_item_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY artemis_config_revision
    ADD CONSTRAINT fk_artemis_config_revision_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS artemis_config_revision CASCADE;
--rollback DROP TABLE IF EXISTS artemis_config_owned_item CASCADE;
--rollback DROP TABLE IF EXISTS artemis_config_node_state CASCADE;
--rollback DROP TABLE IF EXISTS artemis_config_declaration CASCADE;
--rollback DROP TABLE IF EXISTS artemis_config_apply CASCADE;

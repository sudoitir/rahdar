--liquibase formatted sql

-- The feature/plugins module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:feature-plugins-0001-baseline splitStatements:true
CREATE TABLE plugin_message_registration (
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    concurrency integer DEFAULT 1 NOT NULL,
    plugin_id text NOT NULL,
    reg_key text NOT NULL,
    source_kind text NOT NULL,
    source_path text NOT NULL,
    mode text NOT NULL,
    id uuid DEFAULT uuidv7() NOT NULL,
    cluster_id uuid NOT NULL,
    acting_user_id uuid NOT NULL,
    CONSTRAINT ck_plugin_message_registration_concurrency CHECK ((((mode = 'TAP'::text) AND (concurrency = 1)) OR ((mode = 'CONSUME'::text) AND (concurrency >= 1) AND (concurrency <= 32)))),
    CONSTRAINT ck_plugin_message_registration_mode CHECK ((mode = ANY (ARRAY['TAP'::text, 'CONSUME'::text])))
);

CREATE TABLE plugin_message_registration_node (
    updated_at timestamp with time zone NOT NULL,
    dropped_copies bigint,
    state text NOT NULL,
    detail text,
    id uuid DEFAULT uuidv7() NOT NULL,
    registration_id uuid NOT NULL,
    node_id uuid,
    releases_counted boolean DEFAULT false NOT NULL
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05');

CREATE TABLE plugin_owner_work (
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    plugin_id text NOT NULL,
    work_key text NOT NULL,
    needs text NOT NULL,
    state text NOT NULL,
    reason text,
    id uuid DEFAULT uuidv7() NOT NULL,
    owner_user_id uuid NOT NULL,
    CONSTRAINT ck_plugin_owner_work_state CHECK ((state = ANY (ARRAY['ACTIVE'::text, 'SUSPENDED'::text])))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.05');

-- A user's plugin uploads in the current hour, counted for all replicas (ADR-0196).
CREATE UNLOGGED TABLE upload_window (
    window_ends_at timestamp with time zone NOT NULL,
    count integer NOT NULL,
    user_id uuid NOT NULL
)
WITH (fillfactor='50', autovacuum_vacuum_scale_factor='0.0', autovacuum_vacuum_threshold='50');

ALTER TABLE ONLY upload_window
    ADD CONSTRAINT pk_upload_window PRIMARY KEY (user_id);

ALTER TABLE ONLY plugin_message_registration
    ADD CONSTRAINT pk_plugin_message_registration PRIMARY KEY (id);

ALTER TABLE ONLY plugin_message_registration_node
    ADD CONSTRAINT pk_plugin_message_registration_node PRIMARY KEY (id);

ALTER TABLE ONLY plugin_message_registration_node
    ADD CONSTRAINT uq_plugin_message_registration_node UNIQUE NULLS NOT DISTINCT (registration_id, node_id);

ALTER TABLE ONLY plugin_owner_work
    ADD CONSTRAINT pk_plugin_owner_work PRIMARY KEY (id);

ALTER TABLE ONLY plugin_message_registration
    ADD CONSTRAINT uq_plugin_message_registration_key UNIQUE (plugin_id, reg_key);

ALTER TABLE ONLY plugin_owner_work
    ADD CONSTRAINT uq_plugin_owner_work_key UNIQUE (plugin_id, work_key);

CREATE INDEX ix_plugin_message_registration_node_node_id ON plugin_message_registration_node USING btree (node_id);

ALTER TABLE ONLY plugin_message_registration_node
    ADD CONSTRAINT fk_plugin_message_registration_node_node FOREIGN KEY (node_id) REFERENCES broker_node(id) ON DELETE CASCADE;

ALTER TABLE ONLY plugin_message_registration_node
    ADD CONSTRAINT fk_plugin_message_registration_node_registration FOREIGN KEY (registration_id) REFERENCES plugin_message_registration(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS upload_window CASCADE;
--rollback DROP TABLE IF EXISTS plugin_owner_work CASCADE;
--rollback DROP TABLE IF EXISTS plugin_message_registration_node CASCADE;
--rollback DROP TABLE IF EXISTS plugin_message_registration CASCADE;

--liquibase formatted sql

-- The feature/transfer module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:feature-transfer-0001-baseline splitStatements:true
CREATE TABLE transfer_copied (
    message_id bigint NOT NULL,
    run_id uuid NOT NULL
)
WITH (fillfactor='100', autovacuum_vacuum_scale_factor='0.01', autovacuum_analyze_scale_factor='0.01', autovacuum_vacuum_cost_delay='2');

CREATE TABLE transfer_run (
    t0 timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    started_at timestamp with time zone,
    updated_at timestamp with time zone,
    finished_at timestamp with time zone,
    estimate bigint,
    estimate_bytes bigint,
    staged bigint DEFAULT 0 NOT NULL,
    delivered bigint DEFAULT 0 NOT NULL,
    not_transferred bigint DEFAULT 0 NOT NULL,
    expired bigint DEFAULT 0 NOT NULL,
    returned bigint DEFAULT 0 NOT NULL,
    bytes bigint DEFAULT 0 NOT NULL,
    audit_event_id bigint,
    target_audit_event_id bigint,
    stop_requested_at timestamp with time zone,
    id_cursor integer DEFAULT 0 NOT NULL,
    mode text NOT NULL,
    state text NOT NULL,
    source_subscription text NOT NULL,
    source_destination text,
    source_native_attributes jsonb DEFAULT '{}'::jsonb NOT NULL,
    source_node_name text NOT NULL,
    source_native_node_id text NOT NULL,
    target_subscription text NOT NULL,
    target_destination text,
    target_native_attributes jsonb DEFAULT '{}'::jsonb NOT NULL,
    target_node_name text NOT NULL,
    target_native_node_id text NOT NULL,
    plan_hash text NOT NULL,
    username text NOT NULL,
    last_error text,
    error_snippet text,
    selection jsonb NOT NULL,
    findings jsonb NOT NULL,
    id uuid DEFAULT uuidv7() NOT NULL,
    source_cluster_id uuid NOT NULL,
    source_node_id uuid NOT NULL,
    target_cluster_id uuid NOT NULL,
    target_node_id uuid NOT NULL,
    operator_id uuid,
    same_node boolean NOT NULL,
    override_cap boolean DEFAULT false NOT NULL,
    replica_id uuid,
    CONSTRAINT ck_transfer_run_mode CHECK ((mode = ANY (ARRAY['MOVE'::text, 'COPY'::text]))),
    CONSTRAINT ck_transfer_run_state CHECK ((state = ANY (ARRAY['PREVIEWED'::text, 'RUNNING'::text, 'WAITING_FOR_CAPACITY'::text, 'RETURNING'::text, 'SUCCEEDED'::text, 'PARTIAL'::text, 'STOPPED'::text, 'INTERRUPTED'::text, 'FAILED'::text, 'RETURNED'::text])))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.02', autovacuum_analyze_scale_factor='0.02', autovacuum_vacuum_cost_delay='2');

ALTER TABLE ONLY transfer_copied
    ADD CONSTRAINT pk_transfer_copied PRIMARY KEY (run_id, message_id);

ALTER TABLE ONLY transfer_run
    ADD CONSTRAINT pk_transfer_run PRIMARY KEY (id);

CREATE INDEX ix_transfer_run_preview_expiry ON transfer_run USING btree (expires_at) WHERE (state = 'PREVIEWED'::text);

CREATE INDEX ix_transfer_run_source ON transfer_run USING btree (source_cluster_id, created_at DESC);

CREATE INDEX ix_transfer_run_target ON transfer_run USING btree (target_cluster_id, created_at DESC);

CREATE UNIQUE INDEX ux_transfer_run_active ON transfer_run USING btree (source_cluster_id, source_subscription) WHERE (state = ANY (ARRAY['RUNNING'::text, 'WAITING_FOR_CAPACITY'::text, 'RETURNING'::text]));

ALTER TABLE ONLY transfer_copied
    ADD CONSTRAINT fk_transfer_copied_run FOREIGN KEY (run_id) REFERENCES transfer_run(id) ON DELETE CASCADE;

ALTER TABLE ONLY transfer_run
    ADD CONSTRAINT fk_transfer_run_source_cluster FOREIGN KEY (source_cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY transfer_run
    ADD CONSTRAINT fk_transfer_run_target_cluster FOREIGN KEY (target_cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS transfer_run CASCADE;
--rollback DROP TABLE IF EXISTS transfer_copied CASCADE;

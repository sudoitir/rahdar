--liquibase formatted sql

-- The feature/bulk module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:feature-bulk-0001-baseline splitStatements:true
CREATE TABLE bulk_run (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    estimate bigint,
    audit_event_id bigint,
    stop_requested_at timestamp with time zone,
    total_items integer NOT NULL,
    succeeded integer DEFAULT 0 NOT NULL,
    failed integer DEFAULT 0 NOT NULL,
    skipped integer DEFAULT 0 NOT NULL,
    operation text NOT NULL,
    status text NOT NULL,
    username text NOT NULL,
    plan_hash text NOT NULL,
    error text,
    selection jsonb NOT NULL,
    options jsonb NOT NULL,
    id uuid DEFAULT uuidv7() NOT NULL,
    cluster_id uuid NOT NULL,
    estimate_complete boolean NOT NULL,
    override_cap boolean DEFAULT false NOT NULL,
    continue_on_failure boolean DEFAULT false NOT NULL,
    replica_id uuid,
    CONSTRAINT ck_bulk_run_operation CHECK ((operation = ANY (ARRAY['PAUSE'::text, 'RESUME'::text, 'PURGE'::text, 'DELETE'::text]))),
    CONSTRAINT ck_bulk_run_status CHECK ((status = ANY (ARRAY['PREVIEWED'::text, 'RUNNING'::text, 'SUCCEEDED'::text, 'PARTIAL'::text, 'FAILED'::text, 'STOPPED'::text, 'INTERRUPTED'::text])))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.05');

CREATE TABLE bulk_run_item (
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    affected bigint,
    ordinal integer NOT NULL,
    subscription text NOT NULL,
    status text NOT NULL,
    error text,
    estimate jsonb NOT NULL,
    outcome jsonb,
    id uuid DEFAULT uuidv7() NOT NULL,
    run_id uuid NOT NULL,
    CONSTRAINT ck_bulk_run_item_status CHECK ((status = ANY (ARRAY['PENDING'::text, 'REFUSED'::text, 'RUNNING'::text, 'SUCCEEDED'::text, 'PARTIAL'::text, 'FAILED'::text, 'SKIPPED'::text, 'CANCELLED'::text, 'UNKNOWN'::text])))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.02', autovacuum_analyze_scale_factor='0.02', autovacuum_vacuum_cost_delay='2');

ALTER TABLE ONLY bulk_run
    ADD CONSTRAINT pk_bulk_run PRIMARY KEY (id);

ALTER TABLE ONLY bulk_run_item
    ADD CONSTRAINT pk_bulk_run_item PRIMARY KEY (id);

CREATE INDEX ix_bulk_run_cluster ON bulk_run USING btree (cluster_id, created_at DESC);

CREATE INDEX ix_bulk_run_preview_expiry ON bulk_run USING btree (expires_at) WHERE (status = 'PREVIEWED'::text);

CREATE UNIQUE INDEX ux_bulk_run_item_ordinal ON bulk_run_item USING btree (run_id, ordinal);

CREATE UNIQUE INDEX ux_bulk_run_running ON bulk_run USING btree (cluster_id) WHERE (status = 'RUNNING'::text);

ALTER TABLE ONLY bulk_run
    ADD CONSTRAINT fk_bulk_run_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY bulk_run_item
    ADD CONSTRAINT fk_bulk_run_item_run FOREIGN KEY (run_id) REFERENCES bulk_run(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS bulk_run_item CASCADE;
--rollback DROP TABLE IF EXISTS bulk_run CASCADE;

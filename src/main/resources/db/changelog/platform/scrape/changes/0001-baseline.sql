--liquibase formatted sql

-- The platform/scrape module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:platform-scrape-0001-baseline splitStatements:true
CREATE TABLE metric_sample (
    ts timestamp with time zone NOT NULL,
    value double precision NOT NULL,
    subject_kind text NOT NULL,
    subject_path text NOT NULL,
    metric text NOT NULL,
    cluster_id uuid NOT NULL,
    node_id uuid
)
PARTITION BY RANGE (ts);

CREATE TABLE subscription_snapshot (
    ts timestamp with time zone DEFAULT now() NOT NULL,
    backlog bigint DEFAULT 0 NOT NULL,
    consumer_count bigint DEFAULT 0 NOT NULL,
    in_flight bigint DEFAULT 0 NOT NULL,
    scheduled bigint DEFAULT 0 NOT NULL,
    enqueued bigint DEFAULT 0 NOT NULL,
    acknowledged bigint DEFAULT 0 NOT NULL,
    expired bigint DEFAULT 0 NOT NULL,
    destination text,
    subscription text NOT NULL,
    kind text NOT NULL,
    native_attributes jsonb DEFAULT '{}'::jsonb NOT NULL,
    cluster_id uuid NOT NULL,
    node_id uuid NOT NULL,
    durable boolean DEFAULT true NOT NULL,
    paused boolean DEFAULT false NOT NULL
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.02', autovacuum_analyze_scale_factor='0.02', autovacuum_vacuum_cost_delay='2');

ALTER TABLE metric_sample
    ADD CONSTRAINT uq_metric_sample UNIQUE NULLS NOT DISTINCT (cluster_id, subject_kind, subject_path, metric, node_id, ts);

ALTER TABLE ONLY subscription_snapshot
    ADD CONSTRAINT pk_subscription_snapshot PRIMARY KEY (node_id, subscription);

CREATE INDEX ix_metric_sample_ts_brin ON metric_sample USING brin (ts) WITH (pages_per_range='32');

CREATE INDEX ix_subscription_snapshot_destination ON subscription_snapshot USING btree (cluster_id, destination);

CREATE INDEX ix_subscription_snapshot_cluster ON subscription_snapshot USING btree (cluster_id);

ALTER TABLE ONLY subscription_snapshot
    ADD CONSTRAINT fk_subscription_snapshot_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY subscription_snapshot
    ADD CONSTRAINT fk_subscription_snapshot_node FOREIGN KEY (node_id) REFERENCES broker_node(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS subscription_snapshot CASCADE;
--rollback DROP TABLE IF EXISTS metric_sample CASCADE;

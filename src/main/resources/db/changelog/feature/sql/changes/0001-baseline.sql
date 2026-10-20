--liquibase formatted sql

-- The feature/sql module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:feature-sql-0001-baseline splitStatements:true
CREATE TABLE message_capture_node (
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    captured_from timestamp with time zone,
    dropped_estimate bigint DEFAULT 0 NOT NULL,
    held_bytes bigint DEFAULT 0 NOT NULL,
    capture_state text DEFAULT 'PENDING'::text NOT NULL,
    capture_detail text,
    subscription_id uuid NOT NULL,
    node_id uuid NOT NULL,
    CONSTRAINT ck_message_capture_node_state CHECK ((capture_state = ANY (ARRAY['PENDING'::text, 'ACTIVE'::text, 'DEGRADED'::text, 'FAILED'::text])))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05');

CREATE TABLE message_index (
    observed_at timestamp with time zone NOT NULL,
    last_seen_at timestamp with time zone NOT NULL,
    message_id bigint NOT NULL,
    timestamp_ms bigint DEFAULT 0 NOT NULL,
    expiration_ms bigint DEFAULT 0 NOT NULL,
    size_bytes bigint DEFAULT 0 NOT NULL,
    source_message_id bigint,
    priority integer DEFAULT 4 NOT NULL,
    message_type integer DEFAULT 0 NOT NULL,
    policy_version integer DEFAULT 0 NOT NULL,
    subscription text NOT NULL,
    destination text,
    node_name text NOT NULL,
    correlation_id text,
    group_id text,
    user_id text,
    reply_to text,
    jms_type text,
    body text,
    props jsonb DEFAULT '{}'::jsonb NOT NULL,
    origin text DEFAULT 'SAMPLED'::text NOT NULL,
    orig_destination text,
    sealed bytea,
    body_compression text,
    cluster_id uuid NOT NULL,
    node_id uuid NOT NULL,
    durable boolean DEFAULT true NOT NULL,
    body_truncated boolean DEFAULT false NOT NULL,
    body_base64 boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_message_index_origin CHECK ((origin = ANY (ARRAY['SAMPLED'::text, 'CAPTURED'::text])))
)
PARTITION BY RANGE (observed_at);

CREATE TABLE message_index_subscription (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    capture_from timestamp with time zone DEFAULT now() NOT NULL,
    interval_ms bigint DEFAULT 5000 NOT NULL,
    ring_size bigint DEFAULT 10000 NOT NULL,
    max_bytes bigint DEFAULT '5368709120'::bigint NOT NULL,
    retention_days integer DEFAULT 7 NOT NULL,
    max_rate integer DEFAULT 500 NOT NULL,
    body_cap_bytes integer DEFAULT 262144 NOT NULL,
    subscription_pattern text NOT NULL,
    created_by text,
    mode text DEFAULT 'SAMPLE'::text NOT NULL,
    filter_string text,
    id uuid DEFAULT uuidv7() NOT NULL,
    cluster_id uuid NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    CONSTRAINT ck_message_index_subscription_body_cap CHECK (((body_cap_bytes >= 1024) AND (body_cap_bytes <= 16777216))),
    CONSTRAINT ck_message_index_subscription_interval CHECK ((interval_ms >= 1000)),
    CONSTRAINT ck_message_index_subscription_max_bytes CHECK ((max_bytes > 0)),
    CONSTRAINT ck_message_index_subscription_mode CHECK ((mode = ANY (ARRAY['SAMPLE'::text, 'CAPTURE'::text]))),
    CONSTRAINT ck_message_index_subscription_rate CHECK (((max_rate >= 1) AND (max_rate <= 1000000))),
    CONSTRAINT ck_message_index_subscription_retention CHECK (((retention_days >= 1) AND (retention_days <= 90))),
    CONSTRAINT ck_message_index_subscription_ring CHECK (((ring_size >= 100) AND (ring_size <= 1000000)))
);

CREATE UNLOGGED TABLE sql_query_ticket (
    expires_at timestamp with time zone NOT NULL,
    sql text NOT NULL,
    owner text NOT NULL,
    id uuid NOT NULL,
    cluster_id uuid NOT NULL,
    tail boolean NOT NULL
)
WITH (autovacuum_vacuum_scale_factor='0.0', autovacuum_vacuum_threshold='50');

ALTER TABLE message_index
    ADD CONSTRAINT pk_message_index PRIMARY KEY (node_id, subscription, message_id, observed_at);

ALTER TABLE ONLY message_capture_node
    ADD CONSTRAINT pk_message_capture_node PRIMARY KEY (subscription_id, node_id);

ALTER TABLE ONLY message_index_subscription
    ADD CONSTRAINT pk_message_index_subscription PRIMARY KEY (id);

ALTER TABLE ONLY sql_query_ticket
    ADD CONSTRAINT pk_sql_query_ticket PRIMARY KEY (id);

ALTER TABLE ONLY message_index_subscription
    ADD CONSTRAINT uq_message_index_subscription UNIQUE (cluster_id, subscription_pattern);

CREATE INDEX ix_message_capture_node_node_id ON message_capture_node USING btree (node_id);

CREATE INDEX ix_message_index_body ON message_index USING gin (body gin_trgm_ops);

CREATE INDEX ix_message_index_body_fts ON message_index USING gin (to_tsvector('simple'::regconfig, body)) WHERE ((body IS NOT NULL) AND (NOT body_base64));

CREATE INDEX ix_message_index_lookup ON message_index USING btree (cluster_id, subscription, observed_at DESC);

CREATE INDEX ix_message_index_policy_version ON message_index USING btree (policy_version);

CREATE INDEX ix_message_index_props ON message_index USING gin (props jsonb_path_ops);

CREATE INDEX ix_sql_query_ticket_expires_at ON sql_query_ticket USING btree (expires_at);

ALTER TABLE ONLY message_capture_node
    ADD CONSTRAINT fk_message_capture_node_node FOREIGN KEY (node_id) REFERENCES broker_node(id) ON DELETE CASCADE;

ALTER TABLE ONLY message_capture_node
    ADD CONSTRAINT fk_message_capture_node_subscription FOREIGN KEY (subscription_id) REFERENCES message_index_subscription(id) ON DELETE CASCADE;

ALTER TABLE ONLY message_index_subscription
    ADD CONSTRAINT fk_message_index_subscription_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS sql_query_ticket CASCADE;
--rollback DROP TABLE IF EXISTS message_index_subscription CASCADE;
--rollback DROP TABLE IF EXISTS message_index CASCADE;
--rollback DROP TABLE IF EXISTS message_capture_node CASCADE;

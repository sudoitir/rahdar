--liquibase formatted sql

-- The feature/rr module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:feature-rr-0001-baseline splitStatements:true
CREATE TABLE rr_event (
    ts timestamp with time zone DEFAULT now() NOT NULL,
    seq bigint NOT NULL,
    kind text NOT NULL,
    detail jsonb,
    flow_id uuid NOT NULL,
    node_id uuid
)
PARTITION BY RANGE (ts);

CREATE TABLE rr_expectation (
    deadline_ms integer,
    sample_per_min integer DEFAULT 10 NOT NULL,
    request_destination text NOT NULL,
    correlation_property text,
    reply_destinations text[] DEFAULT '{}'::text[] NOT NULL,
    id uuid DEFAULT uuidv7() NOT NULL,
    cluster_id uuid NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    capture_payload boolean DEFAULT false NOT NULL
);

CREATE TABLE rr_flow (
    requested_at timestamp with time zone,
    replied_at timestamp with time zone,
    deadline_at timestamp with time zone,
    latency_ms bigint,
    observed_at timestamp with time zone,
    request_enqueued_at timestamp with time zone,
    reply_enqueued_at timestamp with time zone,
    request_skew_ms bigint,
    reply_skew_ms bigint,
    latency_bound_ms integer,
    request_destination text,
    reply_destination text,
    reply_kind text NOT NULL,
    state text NOT NULL,
    correlation_id text,
    requester_session text,
    responder_session text,
    request_message_id text,
    reply_message_id text,
    responder_consumer text,
    latency_source text DEFAULT 'OBSERVED'::text NOT NULL,
    id uuid DEFAULT uuidv7() NOT NULL,
    cluster_id uuid NOT NULL,
    node_id uuid,
    CONSTRAINT ck_rr_flow_latency_source CHECK ((latency_source = ANY (ARRAY['OBSERVED'::text, 'MESSAGE_TIMESTAMPS'::text]))),
    CONSTRAINT ck_rr_flow_reply_kind CHECK ((reply_kind = ANY (ARRAY['TEMPORARY'::text, 'SHARED'::text]))),
    CONSTRAINT ck_rr_flow_state CHECK ((state = ANY (ARRAY['AWAITING_REPLY'::text, 'COMPLETED'::text, 'TIMED_OUT'::text, 'ORPHANED'::text, 'RESPONDER_DROPPED'::text, 'ORPHANED_REPLY'::text])))
)
WITH (fillfactor='90', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.05');

ALTER TABLE rr_event ALTER COLUMN seq ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME rr_event_seq_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE rr_event
    ADD CONSTRAINT pk_rr_event PRIMARY KEY (seq, ts);

ALTER TABLE ONLY rr_expectation
    ADD CONSTRAINT pk_rr_expectation PRIMARY KEY (id);

ALTER TABLE ONLY rr_flow
    ADD CONSTRAINT pk_rr_flow PRIMARY KEY (id);

ALTER TABLE ONLY rr_expectation
    ADD CONSTRAINT uq_rr_expectation UNIQUE (cluster_id, request_destination);

CREATE INDEX ix_rr_event_flow ON rr_event USING btree (flow_id, ts);

CREATE INDEX ix_rr_flow_destination ON rr_flow USING btree (cluster_id, request_destination, requested_at DESC);

CREATE INDEX ix_rr_flow_cluster_state ON rr_flow USING btree (cluster_id, state);

CREATE INDEX ix_rr_flow_correlation ON rr_flow USING btree (cluster_id, correlation_id);

CREATE INDEX ix_rr_flow_deadline ON rr_flow USING btree (deadline_at) WHERE (state = 'AWAITING_REPLY'::text);

CREATE INDEX ix_rr_flow_node_id ON rr_flow USING btree (node_id);

CREATE INDEX ix_rr_flow_open_reply ON rr_flow USING btree (cluster_id, reply_destination, correlation_id) WHERE (state = 'AWAITING_REPLY'::text);

CREATE UNIQUE INDEX uq_rr_flow_request ON rr_flow USING btree (cluster_id, request_destination, request_message_id) WHERE (request_message_id IS NOT NULL);

ALTER TABLE ONLY rr_expectation
    ADD CONSTRAINT fk_rr_expectation_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY rr_flow
    ADD CONSTRAINT fk_rr_flow_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY rr_flow
    ADD CONSTRAINT fk_rr_flow_node FOREIGN KEY (node_id) REFERENCES broker_node(id) ON DELETE SET NULL;

COMMENT ON COLUMN rr_flow.request_skew_ms IS 'How far into the future the request claimed to have been produced, once the broker''s own offset is removed. Only forward skew is evidence: a negative value is ordinary queue residency and is never recorded here.';

COMMENT ON COLUMN rr_flow.latency_source IS 'How latency_ms was arrived at. OBSERVED is the difference between two sample ticks and is therefore quantised to the sample interval (latency_bound_ms). MESSAGE_TIMESTAMPS is the difference between the two messages'' own enqueue times, normalised onto Rahdar''s clock, and is only used when neither carries forward skew beyond tolerance (ADR-0053).';
--rollback DROP TABLE IF EXISTS rr_flow CASCADE;
--rollback DROP TABLE IF EXISTS rr_expectation CASCADE;
--rollback DROP TABLE IF EXISTS rr_event CASCADE;

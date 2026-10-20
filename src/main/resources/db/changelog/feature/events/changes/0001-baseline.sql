--liquibase formatted sql

-- The feature/events module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:feature-events-0001-baseline splitStatements:true
CREATE TABLE broker_event (
    occurred_at timestamp with time zone NOT NULL,
    received_at timestamp with time zone DEFAULT now() NOT NULL,
    seq bigint NOT NULL,
    type text NOT NULL,
    destination text,
    subscription text,
    consumer text,
    channel text,
    connection text,
    remote_address text,
    principal text,
    data jsonb,
    cluster_id uuid NOT NULL,
    node_id uuid
)
PARTITION BY RANGE (received_at);

ALTER TABLE broker_event ALTER COLUMN seq ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME broker_event_seq_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE broker_event
    ADD CONSTRAINT pk_broker_event PRIMARY KEY (seq, received_at);

CREATE INDEX ix_broker_event_cluster_seq ON broker_event USING btree (cluster_id, seq DESC);

CREATE INDEX ix_broker_event_cluster_time ON broker_event USING btree (cluster_id, occurred_at DESC);

CREATE INDEX ix_broker_event_node_id ON broker_event USING btree (node_id);

CREATE INDEX ix_broker_event_type ON broker_event USING btree (cluster_id, type, occurred_at DESC);

ALTER TABLE broker_event
    ADD CONSTRAINT fk_broker_event_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE broker_event
    ADD CONSTRAINT fk_broker_event_node FOREIGN KEY (node_id) REFERENCES broker_node(id) ON DELETE SET NULL;
--rollback DROP TABLE IF EXISTS broker_event CASCADE;

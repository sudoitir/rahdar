--liquibase formatted sql

-- The feature/flow module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:feature-flow-0001-baseline splitStatements:true
CREATE TABLE flow_client_edge (
    sampled_at timestamp with time zone NOT NULL,
    rate double precision,
    unacked bigint DEFAULT 0 NOT NULL,
    member_count integer NOT NULL,
    kind text NOT NULL,
    client_id text DEFAULT ''::text NOT NULL,
    user_name text DEFAULT ''::text NOT NULL,
    remote_host text DEFAULT ''::text NOT NULL,
    protocol text DEFAULT ''::text NOT NULL,
    destination text DEFAULT ''::text NOT NULL,
    subscription text DEFAULT ''::text NOT NULL,
    node_id uuid NOT NULL,
    cluster_id uuid NOT NULL,
    stalled boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_flow_client_edge_kind CHECK ((kind = ANY (ARRAY['PRODUCE'::text, 'CONSUME'::text])))
)
WITH (fillfactor='70', autovacuum_vacuum_scale_factor='0.02', autovacuum_analyze_scale_factor='0.02', autovacuum_vacuum_cost_delay='2');

CREATE TABLE flow_demand (
    observed_until timestamp with time zone NOT NULL,
    cluster_id uuid NOT NULL
)
WITH (fillfactor='70', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.1');

CREATE TABLE flow_node_sample (
    sampled_at timestamp with time zone NOT NULL,
    producers_seen integer DEFAULT 0 NOT NULL,
    producers_total integer DEFAULT 0 NOT NULL,
    consumers_seen integer DEFAULT 0 NOT NULL,
    consumers_total integer DEFAULT 0 NOT NULL,
    error text,
    error_kind text,
    node_id uuid NOT NULL,
    cluster_id uuid NOT NULL
)
WITH (fillfactor='70', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.1');

CREATE TABLE flow_route (
    sampled_at timestamp with time zone NOT NULL,
    rate double precision,
    counter bigint DEFAULT 0 NOT NULL,
    route_class text NOT NULL,
    native_kind text NOT NULL,
    name text NOT NULL,
    source text DEFAULT ''::text NOT NULL,
    target text DEFAULT ''::text NOT NULL,
    filter text,
    transformer text,
    node_id uuid NOT NULL,
    cluster_id uuid NOT NULL,
    exclusive boolean DEFAULT false NOT NULL,
    connected boolean DEFAULT true NOT NULL,
    CONSTRAINT ck_flow_route_class CHECK ((route_class = ANY (ARRAY['BINDING'::text, 'FORWARD'::text, 'LINK'::text])))
)
WITH (fillfactor='70', autovacuum_vacuum_scale_factor='0.02', autovacuum_analyze_scale_factor='0.02', autovacuum_vacuum_cost_delay='2');

ALTER TABLE ONLY flow_client_edge
    ADD CONSTRAINT pk_flow_client_edge PRIMARY KEY (node_id, kind, client_id, user_name, remote_host, protocol, destination, subscription);

ALTER TABLE ONLY flow_demand
    ADD CONSTRAINT pk_flow_demand PRIMARY KEY (cluster_id);

ALTER TABLE ONLY flow_node_sample
    ADD CONSTRAINT pk_flow_node_sample PRIMARY KEY (node_id);

ALTER TABLE ONLY flow_route
    ADD CONSTRAINT pk_flow_route PRIMARY KEY (node_id, native_kind, name, source, target);

CREATE INDEX ix_flow_client_edge_cluster ON flow_client_edge USING btree (cluster_id);

CREATE INDEX ix_flow_node_sample_cluster ON flow_node_sample USING btree (cluster_id);

CREATE INDEX ix_flow_route_cluster ON flow_route USING btree (cluster_id);

ALTER TABLE ONLY flow_client_edge
    ADD CONSTRAINT fk_flow_client_edge_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY flow_client_edge
    ADD CONSTRAINT fk_flow_client_edge_node FOREIGN KEY (node_id) REFERENCES broker_node(id) ON DELETE CASCADE;

ALTER TABLE ONLY flow_demand
    ADD CONSTRAINT fk_flow_demand_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY flow_node_sample
    ADD CONSTRAINT fk_flow_node_sample_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY flow_node_sample
    ADD CONSTRAINT fk_flow_node_sample_node FOREIGN KEY (node_id) REFERENCES broker_node(id) ON DELETE CASCADE;

ALTER TABLE ONLY flow_route
    ADD CONSTRAINT fk_flow_route_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY flow_route
    ADD CONSTRAINT fk_flow_route_node FOREIGN KEY (node_id) REFERENCES broker_node(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS flow_route CASCADE;
--rollback DROP TABLE IF EXISTS flow_node_sample CASCADE;
--rollback DROP TABLE IF EXISTS flow_demand CASCADE;
--rollback DROP TABLE IF EXISTS flow_client_edge CASCADE;

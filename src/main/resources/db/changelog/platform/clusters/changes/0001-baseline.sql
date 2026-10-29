--liquibase formatted sql

-- The platform/clusters module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:platform-clusters-0001-baseline splitStatements:true
CREATE TABLE broker_credential (
    kind text NOT NULL,
    username text,
    sealed bytea NOT NULL,
    id uuid DEFAULT uuidv7() NOT NULL,
    cluster_id uuid NOT NULL
);

CREATE TABLE broker_identity (
    kind text NOT NULL,
    identity text NOT NULL,
    cluster_id uuid NOT NULL,
    CONSTRAINT ck_broker_identity_kind CHECK ((kind = ANY (ARRAY['NODE_ID'::text, 'URL'::text, 'CLUSTER_ID'::text])))
);

CREATE TABLE broker_node (
    last_seen_at timestamp with time zone,
    observed_cycle bigint,
    clock_measured_at timestamp with time zone,
    clock_offset_ms bigint,
    url_checked_at timestamp with time zone,
    clock_uncertainty_ms integer,
    native_node_id text,
    name text NOT NULL,
    ha_designation text DEFAULT 'NONE'::text NOT NULL,
    ha_group text,
    state text DEFAULT 'UNKNOWN'::text NOT NULL,
    version text,
    last_error text,
    split_brain text DEFAULT 'NONE'::text NOT NULL,
    url_problem text,
    last_error_kind text,
    id uuid DEFAULT uuidv7() NOT NULL,
    cluster_id uuid NOT NULL,
    active boolean,
    replica_sync boolean,
    CONSTRAINT ck_broker_node_ha_designation CHECK ((ha_designation = ANY (ARRAY['PRIMARY'::text, 'BACKUP'::text, 'NONE'::text]))),
    CONSTRAINT ck_broker_node_split_brain CHECK ((split_brain = ANY (ARRAY['NONE'::text, 'SUSPECTED'::text, 'CRITICAL'::text])))
);

CREATE TABLE broker_node_endpoint (
    plane text NOT NULL,
    protocol text NOT NULL,
    url text NOT NULL,
    source text NOT NULL,
    node_id uuid NOT NULL,
    CONSTRAINT ck_broker_node_endpoint_plane CHECK ((plane = ANY (ARRAY['MANAGEMENT'::text, 'DATA'::text]))),
    CONSTRAINT ck_broker_node_endpoint_source CHECK ((source = ANY (ARRAY['SEED'::text, 'DERIVED'::text, 'DISCOVERED'::text, 'MANUAL'::text])))
);

CREATE TABLE broker_tls (
    truststore_ref text,
    client_cert_ref text,
    id uuid DEFAULT uuidv7() NOT NULL,
    cluster_id uuid NOT NULL,
    verify_hostname boolean DEFAULT true NOT NULL
);

CREATE TABLE cluster (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    management_write_observed_at timestamp with time zone,
    name text NOT NULL,
    description text,
    management_write_status text,
    management_write_reason text,
    management_url_pattern text,
    system text NOT NULL,
    id uuid DEFAULT uuidv7() NOT NULL,
    environment_id uuid,
    read_only boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_cluster_management_write_status CHECK (((management_write_status IS NULL) OR (management_write_status = ANY (ARRAY['AVAILABLE'::text, 'UNAVAILABLE'::text]))))
);

CREATE TABLE cluster_lease (
    expires_at timestamp with time zone NOT NULL,
    replica_id uuid NOT NULL,
    cluster_id uuid NOT NULL
)
WITH (fillfactor='50', autovacuum_vacuum_scale_factor='0.0', autovacuum_vacuum_threshold='50');

CREATE TABLE environment (
    sort_order integer DEFAULT 0 NOT NULL,
    name text NOT NULL,
    colour text,
    id uuid DEFAULT uuidv7() NOT NULL
);

ALTER TABLE ONLY broker_credential
    ADD CONSTRAINT pk_broker_credential PRIMARY KEY (id);

ALTER TABLE ONLY broker_identity
    ADD CONSTRAINT pk_broker_identity PRIMARY KEY (kind, identity);

ALTER TABLE ONLY broker_node
    ADD CONSTRAINT pk_broker_node PRIMARY KEY (id);

ALTER TABLE ONLY broker_node_endpoint
    ADD CONSTRAINT pk_broker_node_endpoint PRIMARY KEY (node_id, plane, protocol);

ALTER TABLE ONLY broker_tls
    ADD CONSTRAINT pk_broker_tls PRIMARY KEY (id);

ALTER TABLE ONLY cluster
    ADD CONSTRAINT pk_cluster PRIMARY KEY (id);

ALTER TABLE ONLY cluster_lease
    ADD CONSTRAINT pk_cluster_lease PRIMARY KEY (cluster_id);

ALTER TABLE ONLY environment
    ADD CONSTRAINT pk_environment PRIMARY KEY (id);

ALTER TABLE ONLY broker_credential
    ADD CONSTRAINT uq_broker_credential_cluster_kind UNIQUE (cluster_id, kind);

ALTER TABLE ONLY broker_node
    ADD CONSTRAINT uq_broker_node_cluster_name UNIQUE (cluster_id, name);

ALTER TABLE ONLY broker_tls
    ADD CONSTRAINT uq_broker_tls_cluster UNIQUE (cluster_id);

ALTER TABLE ONLY cluster
    ADD CONSTRAINT uq_cluster_env_name UNIQUE (environment_id, name);

ALTER TABLE ONLY environment
    ADD CONSTRAINT uq_environment_name UNIQUE (name);

CREATE INDEX ix_broker_identity_cluster ON broker_identity USING btree (cluster_id);

CREATE INDEX ix_broker_node_cluster ON broker_node USING btree (cluster_id);

ALTER TABLE ONLY broker_credential
    ADD CONSTRAINT fk_broker_credential_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY broker_identity
    ADD CONSTRAINT fk_broker_identity_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY broker_node
    ADD CONSTRAINT fk_broker_node_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY broker_node_endpoint
    ADD CONSTRAINT fk_broker_node_endpoint_node FOREIGN KEY (node_id) REFERENCES broker_node(id) ON DELETE CASCADE;

ALTER TABLE ONLY broker_tls
    ADD CONSTRAINT fk_broker_tls_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY cluster
    ADD CONSTRAINT fk_cluster_environment FOREIGN KEY (environment_id) REFERENCES environment(id) ON DELETE SET NULL;

ALTER TABLE ONLY cluster_lease
    ADD CONSTRAINT fk_cluster_lease_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

COMMENT ON COLUMN broker_node.clock_offset_ms IS 'How far ahead of Rahdar this broker''s clock is, in milliseconds; negative means behind. Measured from the Jolokia response timestamp (ADR-0053). NULL means never measured, not zero.';

COMMENT ON COLUMN broker_node.clock_uncertainty_ms IS 'Half the best round trip plus Jolokia''s second granularity. An offset inside this band is indistinguishable from agreement and is never reported as skew.';

COMMENT ON COLUMN cluster.system IS 'The broker system the cluster runs, as the broker SDK names it (artemis). It picks the driver Rahdar connects through.';

COMMENT ON COLUMN broker_node.native_node_id IS 'The broker''s own id for the node; a primary and its backup share it on Artemis.';

COMMENT ON TABLE broker_node_endpoint IS 'Where a node is reached, per plane (MANAGEMENT, DATA) and protocol, and where each address came from: a seed, derived from the cluster''s pattern, discovered from the broker''s topology, or set by an operator.';

COMMENT ON COLUMN cluster.management_write_status IS 'Evidence of an attempted management write (ADR-0049 D5): AVAILABLE once one has succeeded, UNAVAILABLE once one has been refused for an authorization reason, NULL until one is attempted. A write that fails for any other reason must not set this.';
--rollback DROP TABLE IF EXISTS environment CASCADE;
--rollback DROP TABLE IF EXISTS cluster_lease CASCADE;
--rollback DROP TABLE IF EXISTS cluster CASCADE;
--rollback DROP TABLE IF EXISTS broker_tls CASCADE;
--rollback DROP TABLE IF EXISTS broker_node_endpoint CASCADE;
--rollback DROP TABLE IF EXISTS broker_node CASCADE;
--rollback DROP TABLE IF EXISTS broker_identity CASCADE;
--rollback DROP TABLE IF EXISTS broker_credential CASCADE;

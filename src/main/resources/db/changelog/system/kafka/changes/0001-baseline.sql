--liquibase formatted sql

-- The system/kafka module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:system-kafka-0001-baseline splitStatements:true
CREATE TABLE kafka_connection (
    bootstrap_servers text NOT NULL,
    security_protocol text NOT NULL,
    sasl_mechanism text,
    client_id text,
    registry_kind text DEFAULT 'none'::text NOT NULL,
    registry_url text,
    registry_secret bytea,
    cluster_id uuid NOT NULL,
    CONSTRAINT ck_kafka_connection_security_protocol CHECK ((security_protocol = ANY (ARRAY['PLAINTEXT'::text, 'SSL'::text, 'SASL_PLAINTEXT'::text, 'SASL_SSL'::text]))),
    CONSTRAINT ck_kafka_connection_sasl_mechanism CHECK (((sasl_mechanism IS NULL) OR (sasl_mechanism = ANY (ARRAY['PLAIN'::text, 'SCRAM-SHA-256'::text, 'SCRAM-SHA-512'::text, 'OAUTHBEARER'::text])))),
    CONSTRAINT ck_kafka_connection_registry_kind CHECK ((registry_kind = ANY (ARRAY['none'::text, 'confluent'::text])))
);

CREATE TABLE kafka_schema_binding (
    topic text NOT NULL,
    side text NOT NULL,
    serde text NOT NULL,
    cluster_id uuid NOT NULL,
    CONSTRAINT ck_kafka_schema_binding_side CHECK ((side = ANY (ARRAY['key'::text, 'value'::text])))
);

CREATE TABLE kafka_health (
    evaluated_at timestamp with time zone NOT NULL,
    level text NOT NULL,
    serving jsonb NOT NULL,
    notes jsonb NOT NULL,
    cluster_id uuid NOT NULL,
    behind boolean NOT NULL,
    CONSTRAINT ck_kafka_health_level CHECK ((level = ANY (ARRAY['OK'::text, 'DEGRADED'::text, 'CRITICAL'::text, 'UNKNOWN'::text])))
)
WITH (fillfactor='50', autovacuum_vacuum_scale_factor='0.0', autovacuum_vacuum_threshold='50');

ALTER TABLE ONLY kafka_connection
    ADD CONSTRAINT pk_kafka_connection PRIMARY KEY (cluster_id);

ALTER TABLE ONLY kafka_health
    ADD CONSTRAINT pk_kafka_health PRIMARY KEY (cluster_id);

ALTER TABLE ONLY kafka_connection
    ADD CONSTRAINT fk_kafka_connection_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY kafka_health
    ADD CONSTRAINT fk_kafka_health_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY kafka_schema_binding
    ADD CONSTRAINT pk_kafka_schema_binding PRIMARY KEY (cluster_id, topic, side);

ALTER TABLE ONLY kafka_schema_binding
    ADD CONSTRAINT fk_kafka_schema_binding_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

COMMENT ON TABLE kafka_health IS 'The verdict the scrape last formed for a Kafka cluster, which every replica reads instead of asking the brokers: its level, the brokers serving, the notes in words and whether replicas are behind. Rewritten when the verdict changes and at least every half minute.';

COMMENT ON TABLE kafka_connection IS 'How a Kafka cluster is reached beyond its brokers: the bootstrap servers as host:port separated by commas, the security protocol, the SASL mechanism (set exactly when the protocol uses SASL), the client id, and the schema registry (none, or a Confluent-compatible URL). registry_secret is the vault envelope of the registry credential and is never returned. The account and the TLS bundle are the cluster''s broker_credential and broker_tls rows.';

COMMENT ON TABLE kafka_schema_binding IS 'Which serde decodes a topic''s key or value. The binding is stored here and the serde name is passed to the driver on each decode; the driver holds no per-topic state.';
--rollback DROP TABLE IF EXISTS kafka_schema_binding CASCADE;
--rollback DROP TABLE IF EXISTS kafka_health CASCADE;
--rollback DROP TABLE IF EXISTS kafka_connection CASCADE;

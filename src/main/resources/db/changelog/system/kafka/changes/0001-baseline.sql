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
    cluster_id uuid NOT NULL,
    CONSTRAINT ck_kafka_connection_security_protocol CHECK ((security_protocol = ANY (ARRAY['PLAINTEXT'::text, 'SSL'::text, 'SASL_PLAINTEXT'::text, 'SASL_SSL'::text]))),
    CONSTRAINT ck_kafka_connection_sasl_mechanism CHECK (((sasl_mechanism IS NULL) OR (sasl_mechanism = ANY (ARRAY['PLAIN'::text, 'SCRAM-SHA-256'::text, 'SCRAM-SHA-512'::text, 'OAUTHBEARER'::text]))))
);

ALTER TABLE ONLY kafka_connection
    ADD CONSTRAINT pk_kafka_connection PRIMARY KEY (cluster_id);

ALTER TABLE ONLY kafka_connection
    ADD CONSTRAINT fk_kafka_connection_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

COMMENT ON TABLE kafka_connection IS 'How a Kafka cluster is reached beyond its brokers: the bootstrap servers as host:port separated by commas, the security protocol, the SASL mechanism (set exactly when the protocol uses SASL) and the client id. The account and the TLS bundle are the cluster''s broker_credential and broker_tls rows.';
--rollback DROP TABLE IF EXISTS kafka_connection CASCADE;

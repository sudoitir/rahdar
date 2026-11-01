--liquibase formatted sql

-- The system/rabbitmq module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:system-rabbitmq-0001-baseline splitStatements:true
CREATE TABLE rabbitmq_connection (
    watch_interval_seconds integer DEFAULT 10 NOT NULL,
    management_urls text NOT NULL,
    amqp_endpoints text,
    auth text NOT NULL,
    event_vhost text DEFAULT '/'::text NOT NULL,
    cluster_id uuid NOT NULL,
    CONSTRAINT ck_rabbitmq_connection_auth CHECK ((auth = ANY (ARRAY['PASSWORD'::text, 'TOKEN'::text]))),
    CONSTRAINT ck_rabbitmq_connection_watch_interval CHECK ((watch_interval_seconds >= 1))
);

CREATE TABLE rabbitmq_health (
    evaluated_at timestamp with time zone NOT NULL,
    level text NOT NULL,
    serving jsonb NOT NULL,
    notes jsonb NOT NULL,
    cluster_id uuid NOT NULL,
    behind boolean NOT NULL,
    CONSTRAINT ck_rabbitmq_health_level CHECK ((level = ANY (ARRAY['OK'::text, 'DEGRADED'::text, 'CRITICAL'::text, 'UNKNOWN'::text])))
)
WITH (fillfactor='50', autovacuum_vacuum_scale_factor='0.0', autovacuum_vacuum_threshold='50');

ALTER TABLE ONLY rabbitmq_connection
    ADD CONSTRAINT pk_rabbitmq_connection PRIMARY KEY (cluster_id);

ALTER TABLE ONLY rabbitmq_health
    ADD CONSTRAINT pk_rabbitmq_health PRIMARY KEY (cluster_id);

ALTER TABLE ONLY rabbitmq_connection
    ADD CONSTRAINT fk_rabbitmq_connection_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY rabbitmq_health
    ADD CONSTRAINT fk_rabbitmq_health_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

COMMENT ON TABLE rabbitmq_health IS 'The verdict the scrape last formed for a RabbitMQ cluster, which every replica reads instead of asking the nodes: its level, the nodes serving, the notes in words and whether queues are behind. Rewritten when the verdict changes and at least every half minute.';

COMMENT ON TABLE rabbitmq_connection IS 'How a RabbitMQ cluster is reached beyond its nodes: the management API base URLs and the AMQP endpoints (amqp(s)://host:port, none when the cluster is managed without a data plane) separated by commas, whether the account signs in with a password or a token, the virtual host of the event exchange and how often the cluster is read. The account and the TLS bundle are the cluster''s broker_credential and broker_tls rows.';
--rollback DROP TABLE IF EXISTS rabbitmq_health CASCADE;
--rollback DROP TABLE IF EXISTS rabbitmq_connection CASCADE;

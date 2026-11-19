--liquibase formatted sql

-- The throttle a replica move replaced, so it can be put back when the move finishes (ADR-0230).
-- One row per partition. A second Liquibase run does not apply this changeset again.

--changeset rahdar:system-kafka-0002-reassignment splitStatements:true
CREATE TABLE kafka_reassignment (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    operation_id bigint NOT NULL,
    partition integer NOT NULL,
    topic text NOT NULL,
    previous_throttle jsonb NOT NULL,
    added_keys text NOT NULL,
    id uuid DEFAULT uuidv7() NOT NULL,
    cluster_id uuid NOT NULL
);

ALTER TABLE ONLY kafka_reassignment
    ADD CONSTRAINT pk_kafka_reassignment PRIMARY KEY (id);

ALTER TABLE ONLY kafka_reassignment
    ADD CONSTRAINT uq_kafka_reassignment_partition UNIQUE (cluster_id, topic, partition);

ALTER TABLE ONLY kafka_reassignment
    ADD CONSTRAINT fk_kafka_reassignment_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

COMMENT ON TABLE kafka_reassignment IS 'The replication throttle a replica move replaced, keyed by cluster, topic and partition. The scrape writes these values back when the broker no longer lists that reassignment, then deletes the row. A move this product did not start has no row, so its throttle is left alone.';

--rollback DROP TABLE IF EXISTS kafka_reassignment CASCADE;

--liquibase formatted sql

-- The feature/setupreview module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:feature-setupreview-0001-baseline splitStatements:true
CREATE TABLE artemis_setup_finding (
    first_seen_at timestamp with time zone NOT NULL,
    last_seen_at timestamp with time zone NOT NULL,
    code text NOT NULL,
    subject text NOT NULL,
    severity text NOT NULL,
    category text NOT NULL,
    finding jsonb NOT NULL,
    cluster_id uuid NOT NULL,
    CONSTRAINT ck_setup_finding_severity CHECK ((severity = ANY (ARRAY['CRITICAL'::text, 'WARNING'::text, 'INFO'::text])))
)
WITH (fillfactor='70', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.05');

CREATE TABLE artemis_setup_finding_acceptance (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone,
    code text NOT NULL,
    subject text NOT NULL,
    reason text NOT NULL,
    accepted_by text NOT NULL,
    cluster_id uuid NOT NULL
);

CREATE TABLE artemis_setup_review (
    reviewed_at timestamp with time zone NOT NULL,
    duration_ms bigint NOT NULL,
    nodes_total integer NOT NULL,
    nodes_reviewed integer NOT NULL,
    nodes jsonb NOT NULL,
    not_assessed jsonb NOT NULL,
    cluster_id uuid NOT NULL,
    cluster_evaluated boolean NOT NULL
)
WITH (fillfactor='70', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.05');

ALTER TABLE ONLY artemis_setup_finding
    ADD CONSTRAINT pk_artemis_setup_finding PRIMARY KEY (cluster_id, code, subject);

ALTER TABLE ONLY artemis_setup_finding_acceptance
    ADD CONSTRAINT pk_artemis_setup_finding_acceptance PRIMARY KEY (cluster_id, code, subject);

ALTER TABLE ONLY artemis_setup_review
    ADD CONSTRAINT pk_artemis_setup_review PRIMARY KEY (cluster_id);

ALTER TABLE ONLY artemis_setup_finding_acceptance
    ADD CONSTRAINT fk_setup_finding_acceptance_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY artemis_setup_finding
    ADD CONSTRAINT fk_setup_finding_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;

ALTER TABLE ONLY artemis_setup_review
    ADD CONSTRAINT fk_setup_review_cluster FOREIGN KEY (cluster_id) REFERENCES cluster(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS artemis_setup_review CASCADE;
--rollback DROP TABLE IF EXISTS artemis_setup_finding_acceptance CASCADE;
--rollback DROP TABLE IF EXISTS artemis_setup_finding CASCADE;

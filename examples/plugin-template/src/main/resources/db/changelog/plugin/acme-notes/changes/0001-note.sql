--liquibase formatted sql

-- A note on a subscription. cluster_id is a plain uuid, never a foreign key into Rahdar's tables:
-- a plugin's schema may not constrain Rahdar's (activation refuses it).
--changeset acme:0001-note
CREATE TABLE note (
    created_at timestamp with time zone NOT NULL,
    cluster_id uuid NOT NULL,
    id uuid DEFAULT uuidv7() NOT NULL PRIMARY KEY,
    subscription text NOT NULL,
    author text NOT NULL,
    body text NOT NULL
);
CREATE INDEX ix_note_subscription ON note (cluster_id, subscription, created_at DESC);
--rollback DROP TABLE note;

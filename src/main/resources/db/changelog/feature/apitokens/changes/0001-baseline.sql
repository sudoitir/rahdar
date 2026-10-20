--liquibase formatted sql

-- The feature/apitokens module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:feature-apitokens-0001-baseline splitStatements:true
CREATE UNLOGGED TABLE api_request_window (
    minute bigint NOT NULL,
    count integer NOT NULL,
    key uuid NOT NULL
)
WITH (fillfactor='50', autovacuum_vacuum_scale_factor='0.0', autovacuum_vacuum_threshold='100');

CREATE TABLE api_token (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    last_used_at timestamp with time zone,
    revoked_at timestamp with time zone,
    previous_valid_until timestamp with time zone,
    name text NOT NULL,
    prefix text NOT NULL,
    token_hash bytea NOT NULL,
    previous_prefix text,
    previous_token_hash bytea,
    mcp_tools text[] DEFAULT '{}'::text[] NOT NULL,
    id uuid DEFAULT uuidv7() NOT NULL,
    user_id uuid NOT NULL,
    minted_with_mfa boolean DEFAULT false NOT NULL
);

CREATE TABLE api_token_grant (
    action text NOT NULL,
    scope_type text DEFAULT 'GLOBAL'::text NOT NULL,
    resource_type text DEFAULT ''::text NOT NULL,
    resource_pattern text DEFAULT ''::text NOT NULL,
    token_id uuid NOT NULL,
    scope_id uuid DEFAULT '00000000-0000-0000-0000-000000000000'::uuid NOT NULL,
    CONSTRAINT ck_api_token_grant_resource CHECK ((((resource_type = ''::text) AND (resource_pattern = ''::text)) OR ((resource_type = ANY (ARRAY['SUBSCRIPTION'::text, 'DESTINATION'::text])) AND (resource_pattern <> ''::text)))),
    CONSTRAINT ck_api_token_grant_scope CHECK ((scope_type = ANY (ARRAY['GLOBAL'::text, 'ENVIRONMENT'::text, 'CLUSTER'::text])))
);

CREATE TABLE api_token_usage (
    hour timestamp with time zone NOT NULL,
    requests bigint DEFAULT 0 NOT NULL,
    denied bigint DEFAULT 0 NOT NULL,
    limited bigint DEFAULT 0 NOT NULL,
    errors bigint DEFAULT 0 NOT NULL,
    token_id uuid NOT NULL
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.05');

-- A token's requests being served now, counted for all replicas (ADR-0196). The count lapses at
-- expires_at, so a crashed replica's requests do not hold the limit for ever.
CREATE UNLOGGED TABLE request_in_flight (
    expires_at timestamp with time zone NOT NULL,
    count integer NOT NULL,
    key uuid NOT NULL
)
WITH (fillfactor='50', autovacuum_vacuum_scale_factor='0.0', autovacuum_vacuum_threshold='50');

ALTER TABLE ONLY request_in_flight
    ADD CONSTRAINT pk_request_in_flight PRIMARY KEY (key);

ALTER TABLE ONLY api_request_window
    ADD CONSTRAINT pk_api_request_window PRIMARY KEY (key, minute);

ALTER TABLE ONLY api_token
    ADD CONSTRAINT pk_api_token PRIMARY KEY (id);

ALTER TABLE ONLY api_token_grant
    ADD CONSTRAINT pk_api_token_grant PRIMARY KEY (token_id, action, scope_type, scope_id, resource_type, resource_pattern);

ALTER TABLE ONLY api_token_usage
    ADD CONSTRAINT pk_api_token_usage PRIMARY KEY (token_id, hour);

ALTER TABLE ONLY api_token
    ADD CONSTRAINT uq_api_token_prefix UNIQUE (prefix);

CREATE INDEX ix_api_token_user ON api_token USING btree (user_id);

CREATE UNIQUE INDEX uq_api_token_previous_prefix ON api_token USING btree (previous_prefix) WHERE (previous_prefix IS NOT NULL);

ALTER TABLE ONLY api_token_grant
    ADD CONSTRAINT fk_api_token_grant_token FOREIGN KEY (token_id) REFERENCES api_token(id) ON DELETE CASCADE;

ALTER TABLE ONLY api_token_usage
    ADD CONSTRAINT fk_api_token_usage_token FOREIGN KEY (token_id) REFERENCES api_token(id) ON DELETE CASCADE;

ALTER TABLE ONLY api_token
    ADD CONSTRAINT fk_api_token_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;
--rollback DROP TABLE IF EXISTS request_in_flight CASCADE;
--rollback DROP TABLE IF EXISTS api_token_usage CASCADE;
--rollback DROP TABLE IF EXISTS api_token_grant CASCADE;
--rollback DROP TABLE IF EXISTS api_token CASCADE;
--rollback DROP TABLE IF EXISTS api_request_window CASCADE;

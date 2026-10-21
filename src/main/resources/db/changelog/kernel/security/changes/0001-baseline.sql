--liquibase formatted sql

-- The kernel/security module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:kernel-security-0001-baseline splitStatements:true
CREATE TABLE access_change_log (
    at timestamp with time zone DEFAULT now() NOT NULL,
    id bigint NOT NULL,
    actor_id uuid NOT NULL,
    subject_id uuid
)
WITH (fillfactor='100', autovacuum_vacuum_insert_scale_factor='0.0', autovacuum_vacuum_insert_threshold='5000', autovacuum_analyze_scale_factor='0.02');

CREATE TABLE app_user (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    locked_until timestamp with time zone,
    failed_login_count integer DEFAULT 0 NOT NULL,
    username text NOT NULL,
    email text,
    password_hash text,
    provider_id text DEFAULT 'local'::text NOT NULL,
    external_subject text,
    id uuid DEFAULT uuidv7() NOT NULL,
    disabled boolean DEFAULT false NOT NULL,
    must_change_password boolean DEFAULT false NOT NULL
);

CREATE TABLE idempotency_record (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    status integer,
    idem_key text NOT NULL,
    fingerprint text NOT NULL,
    state text NOT NULL,
    content_type text,
    headers text,
    body bytea,
    user_id uuid NOT NULL,
    CONSTRAINT ck_idempotency_record_state CHECK ((state = ANY (ARRAY['PENDING'::text, 'DONE'::text])))
)
WITH (autovacuum_vacuum_scale_factor='0.05');

CREATE TABLE identity_group_mapping (
    provider_id text NOT NULL,
    group_name text NOT NULL,
    scope_type text DEFAULT 'GLOBAL'::text NOT NULL,
    id uuid DEFAULT uuidv7() NOT NULL,
    role_id uuid NOT NULL,
    scope_id uuid DEFAULT '00000000-0000-0000-0000-000000000000'::uuid NOT NULL,
    CONSTRAINT ck_identity_group_mapping_scope CHECK ((scope_type = ANY (ARRAY['GLOBAL'::text, 'ENVIRONMENT'::text, 'CLUSTER'::text])))
);

CREATE TABLE identity_provider_default_role (
    provider_id text NOT NULL,
    role_id uuid NOT NULL
);

CREATE TABLE plugin_secret (
    updated_at timestamp with time zone NOT NULL,
    plugin_id text NOT NULL,
    name text NOT NULL,
    sealed bytea NOT NULL,
    id uuid DEFAULT uuidv7() NOT NULL
);

CREATE TABLE role (
    name text NOT NULL,
    id uuid DEFAULT uuidv7() NOT NULL,
    builtin boolean DEFAULT false NOT NULL,
    requires_mfa boolean DEFAULT false NOT NULL,
    team_assignable boolean DEFAULT false NOT NULL
);

CREATE TABLE role_permission (
    action text NOT NULL,
    role_id uuid NOT NULL
);

CREATE TABLE secret_key_state (
    updated_at timestamp with time zone NOT NULL,
    current_kek_version integer NOT NULL,
    singleton boolean DEFAULT true NOT NULL,
    CONSTRAINT ck_secret_key_state_single CHECK (singleton)
);

CREATE TABLE secret_rotation (
    started_at timestamp with time zone NOT NULL,
    finished_at timestamp with time zone,
    rewrapped bigint DEFAULT 0 NOT NULL,
    remaining bigint DEFAULT 0 NOT NULL,
    from_version integer NOT NULL,
    to_version integer NOT NULL,
    status text NOT NULL,
    started_by text NOT NULL,
    error text,
    id uuid DEFAULT uuidv7() NOT NULL,
    CONSTRAINT ck_secret_rotation_status CHECK ((status = ANY (ARRAY['RUNNING'::text, 'SUCCEEDED'::text, 'FAILED'::text])))
)
WITH (fillfactor='80', autovacuum_vacuum_scale_factor='0.05', autovacuum_analyze_scale_factor='0.05');

CREATE TABLE spring_session (
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    expiry_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    principal_name character varying(100),
    primary_id character(36) NOT NULL,
    session_id character(36) NOT NULL
);

CREATE TABLE spring_session_attributes (
    session_primary_id character(36) NOT NULL,
    attribute_name character varying(200) NOT NULL,
    attribute_bytes bytea NOT NULL
);

CREATE TABLE team (
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    name text NOT NULL,
    id uuid DEFAULT uuidv7() NOT NULL
);

CREATE TABLE team_member (
    principal_type text NOT NULL,
    provider_id text,
    group_name text,
    id uuid DEFAULT uuidv7() NOT NULL,
    team_id uuid NOT NULL,
    user_id uuid,
    role_id uuid NOT NULL,
    CONSTRAINT ck_team_member_principal CHECK ((((principal_type = 'USER'::text) AND (user_id IS NOT NULL) AND (provider_id IS NULL) AND (group_name IS NULL)) OR ((principal_type = 'GROUP'::text) AND (user_id IS NULL) AND (provider_id IS NOT NULL) AND (group_name IS NOT NULL))))
);

CREATE TABLE team_pattern (
    kind text NOT NULL,
    pattern text NOT NULL,
    id uuid DEFAULT uuidv7() NOT NULL,
    team_id uuid NOT NULL,
    cluster_id uuid NOT NULL,
    CONSTRAINT ck_team_pattern_kind CHECK ((kind = ANY (ARRAY['SUBSCRIPTION'::text, 'DESTINATION'::text, 'ANY'::text])))
);

CREATE TABLE team_share (
    kind text NOT NULL,
    pattern text NOT NULL,
    id uuid DEFAULT uuidv7() NOT NULL,
    owner_team_id uuid NOT NULL,
    target_team_id uuid NOT NULL,
    cluster_id uuid NOT NULL,
    role_id uuid NOT NULL,
    CONSTRAINT ck_team_share_distinct CHECK ((owner_team_id <> target_team_id)),
    CONSTRAINT ck_team_share_kind CHECK ((kind = ANY (ARRAY['SUBSCRIPTION'::text, 'DESTINATION'::text, 'ANY'::text])))
);

CREATE TABLE user_group (
    provider_id text NOT NULL,
    group_name text NOT NULL,
    user_id uuid NOT NULL
);

CREATE TABLE user_role (
    scope_type text DEFAULT 'GLOBAL'::text NOT NULL,
    user_id uuid NOT NULL,
    role_id uuid NOT NULL,
    scope_id uuid DEFAULT '00000000-0000-0000-0000-000000000000'::uuid NOT NULL,
    CONSTRAINT ck_user_role_scope CHECK ((scope_type = ANY (ARRAY['GLOBAL'::text, 'ENVIRONMENT'::text, 'CLUSTER'::text])))
);

-- Failed sign-ins by username and source, and by source alone, counted for all replicas (ADR-0196).
CREATE UNLOGGED TABLE login_attempt (
    expires_at timestamp with time zone NOT NULL,
    locked_until timestamp with time zone,
    failures integer NOT NULL,
    key text NOT NULL
)
WITH (fillfactor='50', autovacuum_vacuum_scale_factor='0.0', autovacuum_vacuum_threshold='50');

ALTER TABLE ONLY login_attempt
    ADD CONSTRAINT pk_login_attempt PRIMARY KEY (key);

ALTER TABLE access_change_log ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME access_change_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY access_change_log
    ADD CONSTRAINT pk_access_change_log PRIMARY KEY (id);

ALTER TABLE ONLY app_user
    ADD CONSTRAINT pk_app_user PRIMARY KEY (id);

ALTER TABLE ONLY idempotency_record
    ADD CONSTRAINT pk_idempotency_record PRIMARY KEY (user_id, idem_key);

ALTER TABLE ONLY identity_group_mapping
    ADD CONSTRAINT pk_identity_group_mapping PRIMARY KEY (id);

ALTER TABLE ONLY identity_provider_default_role
    ADD CONSTRAINT pk_identity_provider_default_role PRIMARY KEY (provider_id);

ALTER TABLE ONLY plugin_secret
    ADD CONSTRAINT pk_plugin_secret PRIMARY KEY (id);

ALTER TABLE ONLY role
    ADD CONSTRAINT pk_role PRIMARY KEY (id);

ALTER TABLE ONLY role_permission
    ADD CONSTRAINT pk_role_permission PRIMARY KEY (role_id, action);

ALTER TABLE ONLY secret_key_state
    ADD CONSTRAINT pk_secret_key_state PRIMARY KEY (singleton);

ALTER TABLE ONLY secret_rotation
    ADD CONSTRAINT pk_secret_rotation PRIMARY KEY (id);

ALTER TABLE ONLY team
    ADD CONSTRAINT pk_team PRIMARY KEY (id);

ALTER TABLE ONLY team_member
    ADD CONSTRAINT pk_team_member PRIMARY KEY (id);

ALTER TABLE ONLY team_pattern
    ADD CONSTRAINT pk_team_pattern PRIMARY KEY (id);

ALTER TABLE ONLY team_share
    ADD CONSTRAINT pk_team_share PRIMARY KEY (id);

ALTER TABLE ONLY user_group
    ADD CONSTRAINT pk_user_group PRIMARY KEY (user_id, provider_id, group_name);

ALTER TABLE ONLY user_role
    ADD CONSTRAINT pk_user_role PRIMARY KEY (user_id, role_id, scope_type, scope_id);

ALTER TABLE ONLY spring_session_attributes
    ADD CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name);

ALTER TABLE ONLY spring_session
    ADD CONSTRAINT spring_session_pk PRIMARY KEY (primary_id);

ALTER TABLE ONLY app_user
    ADD CONSTRAINT uq_app_user_provider_subject UNIQUE (provider_id, external_subject);

ALTER TABLE ONLY app_user
    ADD CONSTRAINT uq_app_user_username UNIQUE (username);

ALTER TABLE ONLY identity_group_mapping
    ADD CONSTRAINT uq_identity_group_mapping UNIQUE (provider_id, group_name, role_id, scope_type, scope_id);

ALTER TABLE ONLY plugin_secret
    ADD CONSTRAINT uq_plugin_secret_name UNIQUE (plugin_id, name);

ALTER TABLE ONLY role
    ADD CONSTRAINT uq_role_name UNIQUE (name);

ALTER TABLE ONLY team_pattern
    ADD CONSTRAINT uq_team_pattern UNIQUE (team_id, cluster_id, kind, pattern);

ALTER TABLE ONLY team_share
    ADD CONSTRAINT uq_team_share UNIQUE (owner_team_id, target_team_id, cluster_id, kind, pattern);

CREATE INDEX ix_access_change_log_actor_at ON access_change_log USING btree (actor_id, at);

CREATE INDEX ix_access_change_log_at ON access_change_log USING btree (at);

CREATE INDEX ix_idempotency_record_created_at ON idempotency_record USING btree (created_at);

CREATE INDEX ix_identity_group_mapping_role_id ON identity_group_mapping USING btree (role_id);

CREATE INDEX ix_identity_provider_default_role_role_id ON identity_provider_default_role USING btree (role_id);

CREATE INDEX ix_team_member_role_id ON team_member USING btree (role_id);

CREATE INDEX ix_team_member_user ON team_member USING btree (user_id) WHERE (user_id IS NOT NULL);

CREATE INDEX ix_team_pattern_cluster ON team_pattern USING btree (cluster_id);

CREATE INDEX ix_team_share_cluster ON team_share USING btree (cluster_id);

CREATE INDEX ix_team_share_role_id ON team_share USING btree (role_id);

CREATE INDEX ix_team_share_target_team_id ON team_share USING btree (target_team_id);

CREATE INDEX ix_user_group_group ON user_group USING btree (provider_id, group_name);

CREATE INDEX ix_user_role_role_id ON user_role USING btree (role_id);

CREATE UNIQUE INDEX spring_session_ix1 ON spring_session USING btree (session_id);

CREATE INDEX spring_session_ix2 ON spring_session USING btree (expiry_time);

CREATE INDEX spring_session_ix3 ON spring_session USING btree (principal_name);

CREATE UNIQUE INDEX uq_app_user_username_lower ON app_user USING btree (lower(username));

CREATE UNIQUE INDEX uq_team_member_group ON team_member USING btree (team_id, provider_id, group_name) WHERE (principal_type = 'GROUP'::text);

CREATE UNIQUE INDEX uq_team_member_user ON team_member USING btree (team_id, user_id) WHERE (principal_type = 'USER'::text);

CREATE UNIQUE INDEX uq_team_name_lower ON team USING btree (lower(name));

CREATE UNIQUE INDEX ux_secret_rotation_running ON secret_rotation USING btree ((true)) WHERE (status = 'RUNNING'::text);

ALTER TABLE ONLY identity_group_mapping
    ADD CONSTRAINT fk_identity_group_mapping_role FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE;

ALTER TABLE ONLY identity_provider_default_role
    ADD CONSTRAINT fk_identity_provider_default_role_role FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE;

ALTER TABLE ONLY role_permission
    ADD CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE;

ALTER TABLE ONLY team_member
    ADD CONSTRAINT fk_team_member_role FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE;

ALTER TABLE ONLY team_member
    ADD CONSTRAINT fk_team_member_team FOREIGN KEY (team_id) REFERENCES team(id) ON DELETE CASCADE;

ALTER TABLE ONLY team_member
    ADD CONSTRAINT fk_team_member_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY team_pattern
    ADD CONSTRAINT fk_team_pattern_team FOREIGN KEY (team_id) REFERENCES team(id) ON DELETE CASCADE;

ALTER TABLE ONLY team_share
    ADD CONSTRAINT fk_team_share_owner FOREIGN KEY (owner_team_id) REFERENCES team(id) ON DELETE CASCADE;

ALTER TABLE ONLY team_share
    ADD CONSTRAINT fk_team_share_role FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE;

ALTER TABLE ONLY team_share
    ADD CONSTRAINT fk_team_share_target FOREIGN KEY (target_team_id) REFERENCES team(id) ON DELETE CASCADE;

ALTER TABLE ONLY user_group
    ADD CONSTRAINT fk_user_group_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY user_role
    ADD CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE;

ALTER TABLE ONLY user_role
    ADD CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE;

ALTER TABLE ONLY spring_session_attributes
    ADD CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id) REFERENCES spring_session(primary_id) ON DELETE CASCADE;

-- Built-in roles and their permissions.
INSERT INTO role (name, builtin, requires_mfa, team_assignable) VALUES
    ('ADMIN', TRUE, TRUE, FALSE),
    ('OPERATOR', TRUE, FALSE, FALSE),
    ('VIEWER', TRUE, FALSE, FALSE),
    ('TEAM_ADMIN', TRUE, FALSE, TRUE),
    ('TEAM_OPERATOR', TRUE, FALSE, TRUE),
    ('TEAM_VIEWER', TRUE, FALSE, TRUE);

INSERT INTO role_permission (role_id, action)
SELECT r.id, p.action
FROM role r
JOIN (VALUES
    ('ADMIN', '*'),
    ('OPERATOR', 'destination:create'),
    ('OPERATOR', 'destination:delete'),
    ('OPERATOR', 'destination:read'),
    ('OPERATOR', 'alert:read'),
    ('OPERATOR', 'alert:write'),
    ('OPERATOR', 'capture:write'),
    ('OPERATOR', 'cluster:read'),
    ('OPERATOR', 'cluster:write'),
    ('OPERATOR', 'connection:close'),
    ('OPERATOR', 'connection:read'),
    ('OPERATOR', 'data:read'),
    ('OPERATOR', 'route:write'),
    ('OPERATOR', 'environment:read'),
    ('OPERATOR', 'governance:read'),
    ('OPERATOR', 'message:delete'),
    ('OPERATOR', 'message:move'),
    ('OPERATOR', 'message:read'),
    ('OPERATOR', 'message:send'),
    ('OPERATOR', 'subscription:create'),
    ('OPERATOR', 'subscription:delete'),
    ('OPERATOR', 'subscription:pause'),
    ('OPERATOR', 'subscription:purge'),
    ('OPERATOR', 'subscription:read'),
    ('OPERATOR', 'subscription:update'),
    ('OPERATOR', 'rr:write'),
    ('OPERATOR', 'settings:read'),
    ('TEAM_ADMIN', 'destination:create'),
    ('TEAM_ADMIN', 'destination:delete'),
    ('TEAM_ADMIN', 'destination:read'),
    ('TEAM_ADMIN', 'route:write'),
    ('TEAM_ADMIN', 'message:delete'),
    ('TEAM_ADMIN', 'message:move'),
    ('TEAM_ADMIN', 'message:read'),
    ('TEAM_ADMIN', 'message:send'),
    ('TEAM_ADMIN', 'subscription:create'),
    ('TEAM_ADMIN', 'subscription:delete'),
    ('TEAM_ADMIN', 'subscription:pause'),
    ('TEAM_ADMIN', 'subscription:purge'),
    ('TEAM_ADMIN', 'subscription:read'),
    ('TEAM_ADMIN', 'subscription:update'),
    ('TEAM_ADMIN', 'team:admin'),
    ('TEAM_OPERATOR', 'destination:create'),
    ('TEAM_OPERATOR', 'destination:delete'),
    ('TEAM_OPERATOR', 'destination:read'),
    ('TEAM_OPERATOR', 'route:write'),
    ('TEAM_OPERATOR', 'message:delete'),
    ('TEAM_OPERATOR', 'message:move'),
    ('TEAM_OPERATOR', 'message:read'),
    ('TEAM_OPERATOR', 'message:send'),
    ('TEAM_OPERATOR', 'subscription:create'),
    ('TEAM_OPERATOR', 'subscription:delete'),
    ('TEAM_OPERATOR', 'subscription:pause'),
    ('TEAM_OPERATOR', 'subscription:purge'),
    ('TEAM_OPERATOR', 'subscription:read'),
    ('TEAM_OPERATOR', 'subscription:update'),
    ('TEAM_VIEWER', 'destination:read'),
    ('TEAM_VIEWER', 'message:read'),
    ('TEAM_VIEWER', 'subscription:read'),
    ('VIEWER', 'destination:read'),
    ('VIEWER', 'alert:read'),
    ('VIEWER', 'cluster:read'),
    ('VIEWER', 'connection:read'),
    ('VIEWER', 'data:read'),
    ('VIEWER', 'environment:read'),
    ('VIEWER', 'governance:read'),
    ('VIEWER', 'message:read'),
    ('VIEWER', 'subscription:read'),
    ('VIEWER', 'settings:read')
) AS p (role_name, action) ON p.role_name = r.name;
--rollback DROP TABLE IF EXISTS login_attempt CASCADE;
--rollback DROP TABLE IF EXISTS user_role CASCADE;
--rollback DROP TABLE IF EXISTS user_group CASCADE;
--rollback DROP TABLE IF EXISTS team_share CASCADE;
--rollback DROP TABLE IF EXISTS team_pattern CASCADE;
--rollback DROP TABLE IF EXISTS team_member CASCADE;
--rollback DROP TABLE IF EXISTS team CASCADE;
--rollback DROP TABLE IF EXISTS spring_session_attributes CASCADE;
--rollback DROP TABLE IF EXISTS spring_session CASCADE;
--rollback DROP TABLE IF EXISTS secret_rotation CASCADE;
--rollback DROP TABLE IF EXISTS secret_key_state CASCADE;
--rollback DROP TABLE IF EXISTS role_permission CASCADE;
--rollback DROP TABLE IF EXISTS role CASCADE;
--rollback DROP TABLE IF EXISTS plugin_secret CASCADE;
--rollback DROP TABLE IF EXISTS identity_provider_default_role CASCADE;
--rollback DROP TABLE IF EXISTS identity_group_mapping CASCADE;
--rollback DROP TABLE IF EXISTS idempotency_record CASCADE;
--rollback DROP TABLE IF EXISTS app_user CASCADE;
--rollback DROP TABLE IF EXISTS access_change_log CASCADE;

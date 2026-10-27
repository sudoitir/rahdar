--liquibase formatted sql

-- The kernel/audit module's schema (ADR-0192): the final shape of its tables, written in one file.
-- Column order (non-negotiable #7), storage parameters, keys and indexes are as they are meant to be.
-- Until the first stable release a change edits this file, and operators recreate the database.

--changeset rahdar:kernel-audit-0001-baseline splitStatements:true
CREATE TABLE audit_event (
    id bigint NOT NULL,
    ts timestamp with time zone DEFAULT now() NOT NULL,
    affected_count bigint,
    parent_id bigint,
    pos_txid xid8 NOT NULL,
    pos_seq bigint NOT NULL,
    action text NOT NULL,
    target_type text,
    target_name text,
    cluster_name text,
    username text,
    outcome text DEFAULT 'PENDING'::text NOT NULL,
    error text,
    request_id text,
    params jsonb,
    source_ip inet,
    outcome_detail jsonb,
    user_id uuid,
    cluster_id uuid,
    node_id uuid,
    dry_run boolean DEFAULT false NOT NULL,
    CONSTRAINT ck_audit_event_outcome CHECK ((outcome = ANY (ARRAY['PENDING'::text, 'SUCCESS'::text, 'FAILURE'::text, 'REFUSED'::text, 'IN_DOUBT'::text])))
)
PARTITION BY RANGE (ts);

ALTER TABLE audit_event ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME audit_event_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE SEQUENCE audit_event_position_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER TABLE audit_event
    ADD CONSTRAINT pk_audit_event PRIMARY KEY (id, ts);

CREATE INDEX ix_audit_event_cluster ON audit_event USING btree (cluster_id, ts DESC);

CREATE INDEX ix_audit_event_parent ON audit_event USING btree (parent_id) WHERE (parent_id IS NOT NULL);

CREATE INDEX ix_audit_event_position ON audit_event USING btree (pos_txid, pos_seq);

CREATE INDEX ix_audit_event_ts ON audit_event USING btree (ts DESC);

CREATE INDEX ix_audit_event_user ON audit_event USING btree (user_id, ts DESC);

COMMENT ON COLUMN audit_event.outcome_detail IS 'Per-node outcome of a cluster-wide fan-out command (ADR-0049 D2/D4): a JSON array of {nodeId, nodeName, status, affected, error}. Null for single-node actions.';

COMMENT ON COLUMN audit_event.pos_txid IS 'Commit-order position, with pos_seq: the id of the transaction that last wrote the row. Set by tg_audit_event_position, never by the writer.';
--rollback DROP TABLE IF EXISTS audit_event CASCADE;
--rollback DROP SEQUENCE IF EXISTS audit_event_position_seq;

--changeset rahdar:kernel-audit-0001-baseline-audit-event-guard splitStatements:false
CREATE FUNCTION audit_event_guard() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF (to_jsonb(NEW) - 'outcome' - 'error' - 'affected_count' - 'outcome_detail' - 'pos_txid' - 'pos_seq')
            <> (to_jsonb(OLD) - 'outcome' - 'error' - 'affected_count' - 'outcome_detail' - 'pos_txid' - 'pos_seq') THEN
        RAISE EXCEPTION 'audit event %: only its outcome may be changed', OLD.id USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.outcome = 'PENDING' THEN
        RETURN NEW;
    END IF;
    IF OLD.outcome = 'REFUSED' AND NEW.outcome = OLD.outcome AND NEW.error IS NOT DISTINCT FROM OLD.error THEN
        RETURN NEW;
    END IF;
    IF NEW.outcome = OLD.outcome AND NEW.error IS NOT DISTINCT FROM OLD.error
            AND NEW.affected_count IS NOT DISTINCT FROM OLD.affected_count
            AND NEW.outcome_detail IS NOT DISTINCT FROM OLD.outcome_detail THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'audit event %: its outcome is final', OLD.id USING ERRCODE = 'check_violation';
END
$$;
--rollback DROP FUNCTION IF EXISTS audit_event_guard CASCADE;

--changeset rahdar:kernel-audit-0001-baseline-audit-event-position splitStatements:false
CREATE FUNCTION audit_event_position() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (to_jsonb(NEW) - 'pos_txid' - 'pos_seq') = (to_jsonb(OLD) - 'pos_txid' - 'pos_seq') THEN
        NEW.pos_txid := OLD.pos_txid;
        NEW.pos_seq := OLD.pos_seq;
        RETURN NEW;
    END IF;
    NEW.pos_txid := pg_current_xact_id();
    NEW.pos_seq := nextval('audit_event_position_seq');
    RETURN NEW;
END
$$;
--rollback DROP FUNCTION IF EXISTS audit_event_position CASCADE;

--changeset rahdar:kernel-audit-0001-baseline-triggers splitStatements:true
CREATE TRIGGER tg_audit_event_guard BEFORE UPDATE ON audit_event FOR EACH ROW EXECUTE FUNCTION audit_event_guard();

CREATE TRIGGER tg_audit_event_position BEFORE INSERT OR UPDATE ON audit_event FOR EACH ROW EXECUTE FUNCTION audit_event_position();
--rollback DROP TRIGGER IF EXISTS tg_audit_event_guard ON audit_event;
--rollback DROP TRIGGER IF EXISTS tg_audit_event_position ON audit_event;

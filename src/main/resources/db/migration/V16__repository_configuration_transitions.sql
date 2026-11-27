ALTER TABLE repositories ADD COLUMN configuration_revision bigint NOT NULL DEFAULT 1 CHECK (configuration_revision>0);
ALTER TABLE repositories ADD COLUMN desired_enabled boolean NOT NULL DEFAULT true;
UPDATE repositories SET desired_enabled=enabled;
ALTER TABLE index_generations ADD COLUMN repository_revision bigint NOT NULL DEFAULT 1 CHECK (repository_revision>0);
CREATE INDEX index_generations_repository_revision_idx ON index_generations(repository_id,repository_revision,generation);

CREATE FUNCTION require_current_repository_configuration() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE current_revision bigint; active boolean;
BEGIN
  IF NEW.published THEN
    SELECT configuration_revision,enabled INTO current_revision,active FROM repositories WHERE id=NEW.repository_id FOR SHARE;
    IF current_revision IS DISTINCT FROM NEW.repository_revision OR active IS DISTINCT FROM true THEN
      RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='generation publication requires the current enabled repository configuration';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER index_generation_configuration_guard BEFORE INSERT OR UPDATE OF published ON index_generations
  FOR EACH ROW EXECUTE FUNCTION require_current_repository_configuration();

-- Survives repository deletion: old mirrors must not be forgotten after a rename/purge.
CREATE TABLE repository_lifecycle_work (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  repository_id uuid NOT NULL,
  target_revision bigint NOT NULL,
  repository_root text NOT NULL,
  previous_repository jsonb NOT NULL,
  reset_mirror boolean NOT NULL,
  state text NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','RUNNING','DONE','FAILED')),
  attempt integer NOT NULL DEFAULT 0,
  claim_token uuid,
  claim_until timestamptz,
  available_at timestamptz NOT NULL DEFAULT now(),
  error text,
  previous_webhook_id bigint,
  previous_webhook_callback text,
  previous_provider_repository_id bigint,
  webhook_retirement_state text NOT NULL DEFAULT 'NOT_APPLICABLE'
    CHECK (webhook_retirement_state IN ('NOT_APPLICABLE','QUEUED','RUNNING','DONE','BLOCKED','FAILED')),
  webhook_retirement_outcome text CHECK (webhook_retirement_outcome IN ('DELETED','ABSENT','REUSED')),
  webhook_retirement_error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(repository_id,target_revision)
);
CREATE INDEX repository_lifecycle_pending_idx ON repository_lifecycle_work(repository_root,available_at) WHERE state IN ('QUEUED','RUNNING');
CREATE INDEX repository_lifecycle_blocked_repository_idx ON repository_lifecycle_work(repository_id) WHERE state<>'DONE';
CREATE INDEX repository_lifecycle_retirement_idx ON repository_lifecycle_work(repository_root,available_at) WHERE state='DONE' AND webhook_retirement_state IN ('QUEUED','RUNNING');
ALTER TABLE repository_onboarding ADD COLUMN webhook_callback_url text;
ALTER TABLE repository_onboarding ADD COLUMN provider_repository_id bigint;

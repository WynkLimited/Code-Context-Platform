CREATE TABLE contract_refresh_jobs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  team text NOT NULL,
  origin_repository_id uuid NOT NULL REFERENCES repositories(id),
  origin_generation_id uuid NOT NULL REFERENCES index_generations(id),
  repository_id uuid NOT NULL REFERENCES repositories(id),
  changed_keys text[] NOT NULL,
  idempotency_key text NOT NULL UNIQUE,
  state text NOT NULL CHECK (state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
  attempt integer NOT NULL DEFAULT 0,
  available_at timestamptz NOT NULL DEFAULT now(),
  lease_expires_at timestamptz,
  error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (origin_repository_id <> repository_id)
);

CREATE INDEX contract_refresh_jobs_queue_idx
  ON contract_refresh_jobs (available_at, created_at)
  WHERE state = 'QUEUED';

CREATE INDEX contract_refresh_jobs_lease_idx
  ON contract_refresh_jobs (lease_expires_at)
  WHERE state = 'RUNNING';

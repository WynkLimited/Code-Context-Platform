CREATE TABLE repository_onboarding (
  repository_id uuid PRIMARY KEY REFERENCES repositories(id) ON DELETE CASCADE,
  initial_job_id uuid REFERENCES index_jobs(id) ON DELETE SET NULL,
  webhook_state text NOT NULL DEFAULT 'BLOCKED'
    CHECK (webhook_state IN ('BLOCKED','QUEUED','RUNNING','READY','FAILED','NOT_APPLICABLE')),
  webhook_id bigint,
  webhook_attempt integer NOT NULL DEFAULT 0,
  webhook_claim uuid,
  webhook_claim_until timestamptz,
  webhook_available_at timestamptz NOT NULL DEFAULT now(),
  webhook_error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX repository_onboarding_webhook_queue_idx
  ON repository_onboarding(webhook_available_at)
  WHERE webhook_state IN ('QUEUED','RUNNING');

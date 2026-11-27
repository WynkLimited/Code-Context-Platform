-- No FK to generations: external cleanup must survive deletion of the generation.
CREATE TABLE generation_cleanup_outbox (
  generation_id uuid PRIMARY KEY,
  repository_id uuid NOT NULL,
  owner text NOT NULL,
  name text NOT NULL,
  attempt integer NOT NULL DEFAULT 0,
  state text NOT NULL DEFAULT 'QUEUED' CHECK (state IN ('QUEUED','DONE','FAILED')),
  available_at timestamptz NOT NULL DEFAULT now(),
  error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX generation_cleanup_pending_idx ON generation_cleanup_outbox(available_at)
  WHERE state='QUEUED';

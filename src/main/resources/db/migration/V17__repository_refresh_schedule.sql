ALTER TABLE repositories ADD COLUMN refresh_due_at timestamptz NOT NULL DEFAULT (now()+interval '5 minutes');
ALTER TABLE repositories ADD COLUMN last_refresh_scheduled_at timestamptz;
-- Audit pointer intentionally survives job-history cleanup; status then reports MISSING.
ALTER TABLE repositories ADD COLUMN last_refresh_job_id uuid;
CREATE INDEX repositories_refresh_due_idx ON repositories(refresh_due_at,id) WHERE enabled;

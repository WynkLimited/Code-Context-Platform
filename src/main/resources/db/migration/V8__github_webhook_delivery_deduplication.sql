CREATE TABLE webhook_deliveries (
  delivery_id uuid PRIMARY KEY,
  repository_id uuid NOT NULL REFERENCES repositories(id),
  payload_sha256 char(64) NOT NULL,
  job_id uuid REFERENCES index_jobs(id),
  received_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX webhook_deliveries_repository_received_idx
  ON webhook_deliveries(repository_id, received_at DESC);

CREATE TABLE repository_index_leases (
  repository_id uuid PRIMARY KEY REFERENCES repositories(id) ON DELETE CASCADE,
  lease_token uuid NOT NULL UNIQUE,
  lease_expires_at timestamptz NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX repository_index_leases_expiry_idx
  ON repository_index_leases (lease_expires_at);

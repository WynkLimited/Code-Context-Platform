ALTER TABLE repository_onboarding ADD COLUMN provider_installation_id bigint;
ALTER TABLE repository_onboarding ADD COLUMN provider_host text;
UPDATE repository_onboarding o SET provider_host=lower(substring(r.clone_url from '^https://([^/:]+)'))
FROM repositories r WHERE r.id=o.repository_id AND o.provider_repository_id IS NOT NULL;
CREATE INDEX onboarding_app_installation_idx ON repository_onboarding(provider_host,provider_installation_id) WHERE webhook_mode='APP';

-- A common receipt prevents a delivery ID being reused across push and installation events.
CREATE TABLE github_event_receipts (
  delivery_id uuid PRIMARY KEY,
  event text NOT NULL,
  payload_sha256 char(64) NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now()
);
-- Row-serialized increments order committed invalidations; sequence MAX alone would not.
CREATE TABLE github_installation_epochs (
  provider_host text NOT NULL,
  installation_id bigint NOT NULL CHECK (installation_id>0),
  version bigint NOT NULL DEFAULT 1 CHECK (version>0),
  last_delivery_id uuid NOT NULL REFERENCES github_event_receipts(delivery_id),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(provider_host,installation_id)
);

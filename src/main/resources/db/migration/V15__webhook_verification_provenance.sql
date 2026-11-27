ALTER TABLE repository_onboarding
  ADD COLUMN webhook_mode text NOT NULL DEFAULT 'REPOSITORY'
    CHECK (webhook_mode IN ('REPOSITORY','APP','NONE')),
  ADD COLUMN webhook_verified_at timestamptz,
  ADD COLUMN webhook_configuration_hash char(64);
UPDATE repository_onboarding SET webhook_mode='NONE' WHERE webhook_state='NOT_APPLICABLE';
-- Existing READY rows are not assigned a fabricated verification time or delivery proof.
ALTER TABLE webhook_deliveries ADD COLUMN signing_key_fingerprint char(64);
CREATE INDEX webhook_deliveries_key_idx
  ON webhook_deliveries(repository_id,signing_key_fingerprint,received_at DESC);

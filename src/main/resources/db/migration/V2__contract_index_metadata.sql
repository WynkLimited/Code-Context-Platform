ALTER TABLE contracts ADD COLUMN metadata jsonb NOT NULL DEFAULT '{}';

CREATE TABLE contract_index_metadata (
  generation_id uuid PRIMARY KEY REFERENCES index_generations(id) ON DELETE CASCADE,
  index_schema_version text NOT NULL,
  extractor_versions jsonb NOT NULL,
  compatibility_rules_version text NOT NULL,
  generator_version text NOT NULL,
  generated_at timestamptz NOT NULL,
  index_hash text NOT NULL,
  export_count integer NOT NULL,
  import_count integer NOT NULL
);

CREATE INDEX contracts_generation_role_idx ON contracts(generation_id, role);
CREATE INDEX contracts_repo_lookup_idx ON contracts(canonical_key, role, generation_id);

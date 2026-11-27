CREATE TABLE contract_extractions (
  generation_id uuid NOT NULL REFERENCES index_generations(id) ON DELETE CASCADE,
  extractor_id text NOT NULL,
  extractor_version text NOT NULL,
  input_fingerprint text NOT NULL CHECK (length(input_fingerprint)=64),
  raw_extraction jsonb NOT NULL,
  reused_from_generation uuid,
  PRIMARY KEY(generation_id,extractor_id)
);

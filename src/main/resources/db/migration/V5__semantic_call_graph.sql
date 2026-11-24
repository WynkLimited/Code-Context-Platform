CREATE TABLE semantic_symbols (
  id bigserial PRIMARY KEY,
  repository_id uuid NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
  generation_id uuid NOT NULL REFERENCES index_generations(id) ON DELETE CASCADE,
  commit_sha text NOT NULL,
  symbol_key text NOT NULL,
  declaring_type text NOT NULL,
  signature text NOT NULL,
  symbol_kind text NOT NULL CHECK (symbol_kind IN ('METHOD','CONSTRUCTOR','EXTERNAL_METHOD','EXTERNAL_CONSTRUCTOR')),
  resolution_status text NOT NULL CHECK (resolution_status IN ('RESOLVED','AMBIGUOUS','UNRESOLVED','UNSUPPORTED','INCOMPLETE')),
  UNIQUE(generation_id,symbol_key)
);
ALTER TABLE semantic_call_sites ADD COLUMN caller_symbol_id bigint REFERENCES semantic_symbols(id);
ALTER TABLE semantic_call_sites ADD COLUMN target_symbol_id bigint REFERENCES semantic_symbols(id);
CREATE TABLE semantic_call_edges (
  id bigserial PRIMARY KEY,
  repository_id uuid NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
  generation_id uuid NOT NULL REFERENCES index_generations(id) ON DELETE CASCADE,
  call_site_id bigint NOT NULL UNIQUE REFERENCES semantic_call_sites(id) ON DELETE CASCADE,
  caller_symbol_id bigint REFERENCES semantic_symbols(id),
  target_symbol_id bigint REFERENCES semantic_symbols(id),
  resolution_status text NOT NULL CHECK (resolution_status IN ('RESOLVED','AMBIGUOUS','UNRESOLVED','UNSUPPORTED','INCOMPLETE')),
  evidence text NOT NULL,
  confidence real NOT NULL CHECK (confidence >= 0 AND confidence <= 1)
);
CREATE INDEX semantic_call_edges_target_idx ON semantic_call_edges(target_symbol_id,generation_id,resolution_status);

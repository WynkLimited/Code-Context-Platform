CREATE TABLE semantic_call_sites (
  id bigserial PRIMARY KEY,
  repository_id uuid NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
  generation_id uuid NOT NULL REFERENCES index_generations(id) ON DELETE CASCADE,
  commit_sha text NOT NULL,
  file_path text NOT NULL,
  caller_class text,
  caller_method text,
  start_line integer NOT NULL,
  end_line integer NOT NULL,
  invocation text NOT NULL,
  declaring_type text,
  resolved_signature text,
  parameter_types jsonb NOT NULL DEFAULT '[]',
  return_type text,
  resolution_status text NOT NULL CHECK (resolution_status IN ('RESOLVED','AMBIGUOUS','UNRESOLVED','UNSUPPORTED','INCOMPLETE')),
  evidence text NOT NULL,
  confidence real NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
  extractor_version text NOT NULL,
  dependency_artifact text,
  dependency_version text
);
CREATE INDEX semantic_call_sites_target_idx ON semantic_call_sites(declaring_type, resolved_signature, generation_id);
CREATE INDEX semantic_call_sites_repo_idx ON semantic_call_sites(repository_id, generation_id, resolution_status);

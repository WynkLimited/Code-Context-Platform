CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE repositories (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  owner text NOT NULL,
  name text NOT NULL,
  clone_url text NOT NULL,
  team text NOT NULL,
  default_branch text NOT NULL DEFAULT 'main',
  repository_type text NOT NULL DEFAULT 'SERVICE',
  mirror_path text NOT NULL,
  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(owner, name)
);
CREATE TABLE index_jobs (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  repository_id uuid NOT NULL REFERENCES repositories(id),
  commit_sha text NOT NULL,
  idempotency_key text NOT NULL UNIQUE,
  state text NOT NULL CHECK (state IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
  attempt integer NOT NULL DEFAULT 0,
  available_at timestamptz NOT NULL DEFAULT now(),
  error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX index_jobs_queue_idx ON index_jobs(available_at,created_at) WHERE state='QUEUED';
CREATE TABLE index_generations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  repository_id uuid NOT NULL REFERENCES repositories(id),
  commit_sha text NOT NULL,
  generation integer NOT NULL,
  published boolean NOT NULL DEFAULT false,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(repository_id, generation)
);
CREATE UNIQUE INDEX index_generations_current_idx ON index_generations(repository_id) WHERE published;
CREATE TABLE code_nodes (
  id bigserial PRIMARY KEY,
  generation_id uuid NOT NULL REFERENCES index_generations(id) ON DELETE CASCADE,
  node_type text NOT NULL,
  symbol text NOT NULL,
  qualified_name text,
  file_path text NOT NULL,
  start_line integer NOT NULL,
  end_line integer NOT NULL,
  language text,
  metadata jsonb NOT NULL DEFAULT '{}'
);
CREATE TABLE generation_files (
  generation_id uuid NOT NULL REFERENCES index_generations(id) ON DELETE CASCADE,
  file_path text NOT NULL,
  blob_sha text NOT NULL,
  PRIMARY KEY(generation_id,file_path)
);
CREATE INDEX code_nodes_symbol_idx ON code_nodes USING gin (to_tsvector('simple', coalesce(qualified_name,'') || ' ' || symbol));
CREATE TABLE code_edges (
  generation_id uuid NOT NULL REFERENCES index_generations(id) ON DELETE CASCADE,
  source_node bigint NOT NULL REFERENCES code_nodes(id) ON DELETE CASCADE,
  target_node bigint REFERENCES code_nodes(id) ON DELETE CASCADE,
  edge_type text NOT NULL,
  evidence text,
  confidence real NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
  PRIMARY KEY(generation_id, source_node, edge_type, target_node)
);
CREATE TABLE contracts (
  id bigserial PRIMARY KEY,
  generation_id uuid NOT NULL REFERENCES index_generations(id) ON DELETE CASCADE,
  contract_type text NOT NULL,
  canonical_key text NOT NULL,
  role text NOT NULL CHECK (role IN ('EXPORT','IMPORT')),
  symbol text,
  file_path text NOT NULL,
  line integer NOT NULL,
  dependency_version text,
  evidence text NOT NULL,
  confidence real NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
  content_hash text NOT NULL
);
CREATE INDEX contracts_key_idx ON contracts(canonical_key);
CREATE TABLE contract_links (
  provider_contract bigint NOT NULL REFERENCES contracts(id) ON DELETE CASCADE,
  consumer_contract bigint NOT NULL REFERENCES contracts(id) ON DELETE CASCADE,
  confidence real NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
  evidence jsonb NOT NULL DEFAULT '[]',
  PRIMARY KEY(provider_contract, consumer_contract)
);
CREATE TABLE code_chunks (
  id bigserial PRIMARY KEY,
  generation_id uuid NOT NULL REFERENCES index_generations(id) ON DELETE CASCADE,
  file_path text NOT NULL,
  symbol text,
  start_line integer NOT NULL,
  end_line integer NOT NULL,
  content_hash text NOT NULL,
  embedding vector(${vectorDimensions}),
  content text NOT NULL,
  UNIQUE(generation_id, file_path, content_hash)
);
CREATE INDEX code_chunks_embedding_idx ON code_chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX code_chunks_hash_idx ON code_chunks(content_hash);
CREATE TABLE team_repository_access (
  team text NOT NULL,
  repository_id uuid NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
  PRIMARY KEY(team, repository_id)
);

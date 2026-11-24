CREATE TABLE cross_repo_impact_analyses (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  team text NOT NULL,
  origin_repository_id uuid NOT NULL REFERENCES repositories(id),
  before_generation_id uuid REFERENCES index_generations(id),
  after_generation_id uuid NOT NULL REFERENCES index_generations(id),
  before_commit text,
  after_commit text NOT NULL,
  analysis_status text NOT NULL CHECK (analysis_status IN ('COMPLETE','PARTIAL','INCOMPLETE','UNRESOLVED','STALE','FAILED')),
  result jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE(team, origin_repository_id, after_generation_id)
);

CREATE INDEX cross_repo_impact_latest_idx ON cross_repo_impact_analyses(team, origin_repository_id, created_at DESC);

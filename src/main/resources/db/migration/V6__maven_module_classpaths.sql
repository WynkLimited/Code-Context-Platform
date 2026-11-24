CREATE TABLE maven_module_classpaths (
  repository_id uuid NOT NULL REFERENCES repositories(id) ON DELETE CASCADE,
  generation_id uuid NOT NULL REFERENCES index_generations(id) ON DELETE CASCADE,
  commit_sha text NOT NULL,
  module_path text NOT NULL,
  group_id text,
  artifact_id text,
  version text,
  packaging text,
  classpath_fingerprint text,
  resolver_version text NOT NULL,
  resolution_status text NOT NULL CHECK (resolution_status IN ('RESOLVED','INCOMPLETE','UNRESOLVED')),
  reason text,
  resolved_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(generation_id,module_path)
);
CREATE TABLE maven_module_dependencies (
  generation_id uuid NOT NULL,
  module_path text NOT NULL,
  group_id text NOT NULL,
  artifact_id text NOT NULL,
  version text NOT NULL,
  classifier text NOT NULL DEFAULT '',
  scope text NOT NULL,
  transitive boolean NOT NULL,
  optional boolean NOT NULL,
  exclusions jsonb NOT NULL DEFAULT '[]',
  artifact_path text,
  artifact_sha256 text,
  PRIMARY KEY(generation_id,module_path,group_id,artifact_id,version,classifier,scope),
  FOREIGN KEY(generation_id,module_path) REFERENCES maven_module_classpaths(generation_id,module_path) ON DELETE CASCADE
);
CREATE INDEX maven_module_dependencies_coords_idx ON maven_module_dependencies(group_id,artifact_id,version,generation_id);

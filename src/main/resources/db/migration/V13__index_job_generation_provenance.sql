ALTER TABLE index_jobs
    ADD COLUMN requested_commit_sha text,
    ADD COLUMN generation_id uuid REFERENCES index_generations(id) ON DELETE SET NULL,
    ADD COLUMN generation_provenance_unverified boolean NOT NULL DEFAULT true;

-- Before this migration commit_sha held the request; preserve it without inventing history.
UPDATE index_jobs SET requested_commit_sha=commit_sha;
CREATE INDEX index_jobs_generation_idx ON index_jobs(generation_id);
CREATE INDEX index_jobs_legacy_generation_idx ON index_jobs(repository_id,commit_sha)
    WHERE generation_provenance_unverified AND state='SUCCEEDED';
-- Legacy successful jobs intentionally have no guessed generation association.

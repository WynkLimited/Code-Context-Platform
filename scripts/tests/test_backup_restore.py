"""Exercise backup/restore shell failure boundaries. Real database replay is a separate CI drill."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class BackupRestoreScriptsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.calls = self.root / "calls"
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        CALLS=str(self.calls), DATABASE_URL="jdbc:postgresql://example/source",
                        DATABASE_USER="fixture", DATABASE_PASSWORD="do-not-print", BACKUP_DIR=str(self.root / "backups"),
                        RESTORE_URL="postgresql://example/restore")
        self.stub("pg_dump", 'for arg in "$@"; do case "$arg" in --file=*) printf "fixture archive" > "${arg#--file=}";; esac; done')
        self.stub("pg_restore", 'echo restore >> "$CALLS"; exit "${RESTORE_EXIT:-0}"')
        self.stub("psql", 'cat >/dev/null; echo check >> "$CALLS"; exit "${CHECK_EXIT:-0}"')

    def stub(self, name, body):
        file = self.bin / name
        file.write_text("#!/usr/bin/env bash\nset -eu\n" + body + "\n")
        file.chmod(0o700)

    def run_script(self, name, **overrides):
        return subprocess.run(["bash", str(ROOT / "scripts" / name)], env=dict(self.env, **overrides),
                              capture_output=True, text=True)

    def backup(self):
        result = self.run_script("backup-postgres.sh")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("do-not-print", result.stdout + result.stderr)
        return next((self.root / "backups").glob("*/code_context.dump"))

    def test_backup_integrity_permissions_and_successful_restore(self):
        archive = self.backup()
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        self.assertEqual(digest, Path(str(archive) + ".sha256").read_text().strip())
        self.assertEqual(digest, json.loads((archive.parent / "backup-metadata.json").read_text())["sha256"])
        self.assertEqual(0, archive.stat().st_mode & 0o077)
        result = self.run_script("restore-smoke.sh", DUMP_FILE=str(archive))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("check", self.calls.read_text())

    def test_corruption_is_rejected_before_any_database_command(self):
        archive = self.backup()
        archive.write_bytes(b"corrupt")
        result = self.run_script("restore-smoke.sh", DUMP_FILE=str(archive))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("checksum mismatch", result.stderr)
        self.assertFalse(self.calls.exists())

    def test_missing_integrity_evidence_is_rejected(self):
        archive = self.backup()
        Path(str(archive) + ".sha256").unlink()
        self.assertNotEqual(0, self.run_script("restore-smoke.sh", DUMP_FILE=str(archive)).returncode)
        self.assertFalse(self.calls.exists())

    def test_restore_or_schema_validation_failure_is_not_success(self):
        archive = self.backup()
        for override in ({"RESTORE_EXIT": "1"}, {"CHECK_EXIT": "1"}):
            result = self.run_script("restore-smoke.sh", DUMP_FILE=str(archive), **override)
            self.assertNotEqual(0, result.returncode)
            self.assertNotIn("restore smoke passed", result.stdout)

    def test_failed_dump_is_not_left_as_a_backup(self):
        self.stub("pg_dump", 'for arg in "$@"; do case "$arg" in --file=*) printf partial > "${arg#--file=}";; esac; done; exit 1')
        self.assertNotEqual(0, self.run_script("backup-postgres.sh").returncode)
        self.assertEqual([], list((self.root / "backups").glob("*/code_context.dump")))

    def test_ci_drill_rejects_missing_or_unsafe_schema_before_database_access(self):
        for schema in ("", "public", "shared_impact_x;DROP SCHEMA public"):
            result = self.run_script("ci-postgres-restore-drill.sh", CI="true", CCP_ACCEPTANCE_SCHEMA=schema)
            self.assertNotEqual(0, result.returncode)
            self.assertIn("acceptance schema is required", result.stderr)
            self.assertFalse(self.calls.exists())


if __name__ == "__main__":
    unittest.main()

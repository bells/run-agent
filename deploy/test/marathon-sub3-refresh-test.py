#!/usr/bin/env python3
"""Exercise scheduled publication and rollback without official network sources."""

import fcntl
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest


DEPLOY = Path(__file__).resolve().parents[1]
SCOPE = "CN_MAINLAND_CAA_AB_MARATHON"


def snapshot(day):
    return (json.dumps({
        "schema_version": 1, "scope": SCOPE, "year": 2026, "as_of": f"2026-11-{day}",
        "published_at": f"2026-11-{day}T00:00:00Z", "catalog_coverage": "PARTIAL", "events": [],
    }) + "\n").encode()


class RefreshTest(unittest.TestCase):
    def setUp(self):
        temporary = TemporaryDirectory(prefix="sub3 test ")
        self.addCleanup(temporary.cleanup)
        workspace = Path(temporary.name).resolve()
        deployment = workspace / "deploy"
        scripts = workspace / "scripts"
        deployment.mkdir()
        scripts.mkdir()
        self.job = deployment / "marathon-sub3-refresh.py"
        shutil.copyfile(DEPLOY / self.job.name, self.job)
        shutil.copyfile(DEPLOY.parent / "scripts/sync_marathon_events.py", scripts / "sync_marathon_events.py")
        shutil.copyfile(DEPLOY.parent / "scripts/marathon_sub3_snapshot.py", scripts / "marathon_sub3_snapshot.py")
        (scripts / "collect_marathon_sub3.py").write_text(f'''\
SCOPE = {SCOPE!r}

if __name__ == "__main__":
    import argparse
    import os
    from pathlib import Path
    import sys

    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--catalog-pdf", required=True, type=Path)
    parser.add_argument("--announcement-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--dry-run", required=True, action="store_true")
    args = parser.parse_args()
    assert args.catalog_pdf.read_bytes() == b"official catalog"
    assert (args.announcement_dir / "edition.html").read_bytes() == b"official announcement"
    assert args.output == Path(os.environ["RUN_AGENT_DEPLOY_DIR"]) / "marathon-data/marathon-sub3.json"
    sys.stdout.buffer.write(args.manifest.read_bytes())
    if os.environ.get("SUB3_TEST_COLLECTOR_FAIL"):
        sys.exit(4)
''')
        self.data = workspace / "release root" / "marathon-data"
        self.data.mkdir(parents=True)
        self.active = self.data / "marathon-sub3.json"
        self.previous = self.data / ".marathon-sub3.previous.json"
        self.manifest = workspace / "approved manifest.json"
        self.manifest.write_bytes(snapshot("26"))
        pdf = workspace / "approved catalog.pdf"
        pdf.write_bytes(b"official catalog")
        archive = workspace / "archived announcements"
        archive.mkdir()
        (archive / "edition.html").write_bytes(b"official announcement")
        self.environment = {
            **os.environ,
            "RUN_AGENT_DEPLOY_DIR": str(self.data.parent),
            "RUN_AGENT_MARATHON_SUB3_MANIFEST": str(self.manifest),
            "RUN_AGENT_MARATHON_SUB3_CATALOG_PDF": str(pdf),
            "RUN_AGENT_MARATHON_SUB3_ANNOUNCEMENT_DIR": str(archive),
        }

    def execute(self, *args, **environment):
        return subprocess.run(
            [sys.executable, self.job, *args], env={**self.environment, **environment},
            capture_output=True, check=False, timeout=5,
        )

    def test_publish_and_rollback_keep_last_snapshot(self):
        result = self.execute()
        self.assertEqual(0, result.returncode, result.stderr.decode())
        self.assertEqual(snapshot("26"), self.active.read_bytes())
        self.assertEqual(0o644, stat.S_IMODE(self.active.stat().st_mode))
        self.assertFalse(self.previous.exists())

        self.manifest.write_bytes(snapshot("27"))
        result = self.execute("--dry-run")
        self.assertEqual(0, result.returncode, result.stderr.decode())
        self.assertEqual(snapshot("26"), self.active.read_bytes())
        self.assertFalse(self.previous.exists())

        for _ in range(2):
            result = self.execute()
            self.assertEqual(0, result.returncode, result.stderr.decode())
            self.assertEqual(snapshot("27"), self.active.read_bytes())
            self.assertEqual(snapshot("26"), self.previous.read_bytes())

        result = self.execute("--rollback", RUN_AGENT_MARATHON_SUB3_MANIFEST="")
        self.assertEqual(0, result.returncode, result.stderr.decode())
        self.assertEqual(snapshot("26"), self.active.read_bytes())
        self.assertEqual(snapshot("26"), self.previous.read_bytes())

    def test_failed_collector_keeps_active_and_previous(self):
        self.active.write_bytes(snapshot("25"))
        self.previous.write_bytes(snapshot("24"))
        result = self.execute(SUB3_TEST_COLLECTOR_FAIL="1")
        self.assertNotEqual(0, result.returncode)
        self.assertIn(b"exited with status 4", result.stderr)
        self.assertEqual(snapshot("25"), self.active.read_bytes())
        self.assertEqual(snapshot("24"), self.previous.read_bytes())

    def test_invalid_or_oversized_candidate_keeps_active_snapshot(self):
        self.active.write_bytes(snapshot("25"))
        malformed = json.loads(snapshot("26"))
        malformed["events"] = [{"event_id": "bogus"}]
        for candidate, reason in ((b"not JSON", b"Expecting value"),
                                  (json.dumps(malformed).encode(), b"Invalid or repeated sub-three event"),
                                  (b" " * (2 * 1024 * 1024) + snapshot("26"), b"size limit")):
            self.manifest.write_bytes(candidate)
            result = self.execute()
            self.assertNotEqual(0, result.returncode)
            self.assertIn(reason, result.stderr)
            self.assertEqual(snapshot("25"), self.active.read_bytes())
            self.assertFalse(self.previous.exists())

    def test_collector_lock_prevents_concurrent_publication(self):
        self.active.write_bytes(snapshot("25"))
        with (self.data / ".marathon-sub3.json.lock").open("ab") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.execute()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(b"operation is running", result.stderr)
        self.assertEqual(snapshot("25"), self.active.read_bytes())
        self.assertFalse(self.previous.exists())

    def test_rejects_symlink_snapshot_and_relative_release_root(self):
        unrelated = self.data.parent / "unrelated.json"
        unrelated.write_bytes(snapshot("25"))
        self.active.symlink_to(unrelated)
        result = self.execute()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(b"must be a regular file", result.stderr)
        self.assertTrue(self.active.is_symlink())
        self.assertEqual(snapshot("25"), unrelated.read_bytes())
        result = self.execute(RUN_AGENT_DEPLOY_DIR="release root")
        self.assertNotEqual(0, result.returncode)
        self.assertIn(b"must be an absolute path", result.stderr)
        self.assertFalse(self.previous.exists())


if __name__ == "__main__":
    unittest.main()

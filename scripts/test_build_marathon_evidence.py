from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from build_marathon_evidence import publish_corpus

SOURCE = {"id": "sample-2026-guide", "raceId": "sample_marathon", "year": 2026, "kind": "COURSE_GUIDE",
          "title": "示例赛道", "publisher": "示例组委会", "url": "https://example.com/course",
          "publishedAt": "2026-02-01", "aliases": ["示例马拉松"], "paragraphs": ["赛道后半段经过一座桥。"]}


class MarathonEvidenceExportTest(unittest.TestCase):
    def test_export_keeps_provenance_in_markdown_and_search_snapshot(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)
            output = root / "marathon-evidence.json"
            documents = publish_corpus({"schemaVersion": 1, "documents": [SOURCE]}, output, root / "docs")
            self.assertIn(SOURCE["url"], output.read_text())
            self.assertIn(SOURCE["paragraphs"][0], (documents / "sample-2026-guide.md").read_text())
            self.assertIn(SOURCE["url"], (documents / "sample-2026-guide.md").read_text())

    def test_invalid_source_does_not_replace_previously_published_corpus(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)
            output = root / "marathon-evidence.json"
            output.write_text("earlier snapshot")
            invalid = {**SOURCE, "url": "https://private:token@example.com/course"}
            with self.assertRaises(ValueError):
                publish_corpus({"schemaVersion": 1, "documents": [invalid]}, output, root / "docs")
            self.assertEqual(output.read_text(), "earlier snapshot")

from pathlib import Path
import tempfile
import unittest

from render_marathon_sub3_draft import fact_sheet, write_draft

SNAPSHOT = {
    "schema_version": 1, "scope": "CN_MAINLAND_CAA_AB_MARATHON", "year": 2026,
    "as_of": "2026-11-26", "published_at": "2026-11-26T12:00:00Z", "catalog_coverage": "PARTIAL",
    "events": [{"event_id": "beta_2026", "name": "乙城马拉松", "race_date": "2026-10-28", "province": "江苏",
                "cert_level": "A", "edition_source": "https://example.com/beta", "status": "DISCLOSED",
                "disclosed_count": 3200, "source_url": "https://example.com/beta/report",
                "collected_at": "2026-11-26T11:59:00Z", "evidence_sha256": "a" * 64}],
}


class MarathonSub3DraftTest(unittest.TestCase):
    def test_disclosures_stay_out_of_ranking_and_keep_their_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = write_draft(SNAPSHOT, Path(tmp))
            facts = (directory / "facts.json").read_text()
            html = (directory / "preview.html").read_text()
            self.assertEqual(write_draft(SNAPSHOT, Path(tmp)), directory)
            self.assertIn('"rankings": []', facts)
            self.assertIn("口径待核实", html)
            self.assertIn("3200", html)
            self.assertIn("https://example.com/beta/report", html)
            self.assertNotIn("年度前十", html)
            self.assertNotIn("<img", html)

    def test_rejects_unsafe_links_before_writing(self):
        snapshot = {**SNAPSHOT, "events": [{**SNAPSHOT["events"][0], "source_url": "javascript:alert(1)"}]}
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                write_draft(snapshot, Path(tmp))
            self.assertEqual(list(Path(tmp).iterdir()), [])


    def test_exact_aggregate_ranks_without_an_invented_finisher_count(self):
        row = {**SNAPSHOT["events"][0], "status": "VERIFIED", "verified_count": 21,
               "evidence_kind": "OFFICIAL_EXACT_COUNT"}
        row.pop("disclosed_count")
        facts = fact_sheet({**SNAPSHOT, "events": [row]})
        self.assertEqual(facts["rankings"][0]["count"], 21)
        self.assertEqual(facts["disclosed"], [])

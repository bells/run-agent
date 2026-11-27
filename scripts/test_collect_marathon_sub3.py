from datetime import date, datetime, timezone
from hashlib import sha256
from http.client import IncompleteRead
from pathlib import Path
import json
import tempfile
import unittest
from unittest.mock import patch

from collect_marathon_sub3 import PILOT, collect, count_results, disclosure, read_json, run

NOW = datetime(2026, 11, 26, 8, tzinfo=timezone.utc)
RACE = {
    "event_id": "fixture_2026", "name": "示例马拉松", "race_date": "2026-11-01",
    "country": "CN", "province": "江苏", "cert_level": "A", "distance": "MARATHON",
    "state": "COMPLETED", "edition_source": "https://example.com/race",
    "announcement": {"url": "https://example.com/news", "title": "2026示例马拉松",
                     "pattern": "破三人数达(?P<count>[0-9]+)人"},
}


class MarathonSub3Test(unittest.TestCase):
    def test_counts_only_valid_net_results_strictly_under_three_hours(self):
        data = ("bib,net_time,status\n1,2:59:59,FINISHED\n2,3:00:00,FINISHED\n"
                "3,02:59:00,DSQ\n4,,DNF\n5,3:00:01,FINISHED\n")
        self.assertEqual(count_results(data, 5), (1, 3))

    def test_maps_reviewed_vendor_columns_and_uses_only_the_net_time(self):
        columns = {"bib": "号码", "net_time": "芯片计时", "status": "状态"}
        statuses = {"有效": "FINISHED", "取消资格": "DSQ"}
        csv = "号码,枪声计时,芯片计时,状态\n1,3:00:12,2:59:59,有效\n2,3:00:25,3:00:00,有效\n3,2:55:00,2:54:00,取消资格\n"
        self.assertEqual(count_results(csv, 3, columns, statuses), (1, 2))
        with self.assertRaisesRegex(ValueError, "Unknown official result status"):
            count_results(csv.replace("有效", "未审核"), 3, columns, statuses)

    def test_rejects_truncated_or_duplicated_results(self):
        with self.assertRaises(ValueError):
            count_results("bib,net_time,status\n1,2:59:59,FINISHED\n", 2)
        with self.assertRaises(ValueError):
            count_results("bib,net_time,status\n1,2:59:59,FINISHED\n1,2:57:00,FINISHED\n", 2)

    def test_disclosure_is_recorded_without_verified_count(self):
        html = "<h1>2026示例马拉松</h1><p>本届破三人数达26人。</p>"
        with tempfile.TemporaryDirectory() as temp:
            rows = collect({"year": 2026, "events": [RACE]}, Path(temp), NOW, fetch=lambda _: html)["events"]
        self.assertEqual(rows[0]["status"], "DISCLOSED")
        self.assertEqual(rows[0]["disclosed_count"], 26)
        self.assertNotIn("verified_count", rows[0])
        self.assertEqual(rows[0]["source_url"], "https://example.com/news")

    def test_official_announcement_must_prove_net_timing_and_an_exclusive_threshold(self):
        approved = "<h1>2026示例马拉松</h1><p>全马有效净成绩小于3小时的完赛人数为21人。</p>"
        strict = {"url": "https://example.com/strict", "title": "2026示例马拉松",
                  "sha256": sha256(approved.encode()).hexdigest(),
                  "pattern": r"(?P<basis>全马有效净成绩[^的]+)的完赛人数为(?P<count>[0-9]+)人"}
        row = {**RACE, "strict_announcement": strict}
        manifest = {"year": 2026, "events": [row]}
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            result = collect(manifest, root, NOW, fetch=lambda _: approved)["events"][0]
            self.assertEqual(result["verified_count"], 21)
            self.assertEqual(result["evidence_kind"], "OFFICIAL_EXACT_COUNT")
            correction = approved.replace("全马有效", "澄清：网传全马有效").replace("21人。", "999人，该数据有误。")
            excluded = collect(manifest, root, NOW, fetch=lambda _: correction)["events"][0]
            self.assertNotIn("verified_count", excluded)
            inclusive = approved.replace("小于3小时", "3小时及以内")
            manifest["events"][0]["strict_announcement"]["sha256"] = sha256(inclusive.encode()).hexdigest()
            self.assertNotIn("verified_count", collect(manifest, root, NOW, fetch=lambda _: inclusive)["events"][0])

    def test_oversized_snapshot_leaves_the_previous_version_in_place(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({"year": 2026, "events": [RACE]}))
            snapshot = root / "snapshot.json"
            snapshot.write_text('{}')
            with patch("collect_marathon_sub3.MAX_BYTES", 64), patch("collect_marathon_sub3.fetch_html", return_value=""):
                with self.assertRaisesRegex(ValueError, "exceeds size limit"):
                    run(manifest, snapshot, False, NOW)
            self.assertEqual(snapshot.read_text(), '{}')

    def test_archived_strict_and_disclosure_pages_keep_distinct_provenance(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            manifest = root / "manifest.json"
            local = {**RACE, "strict_announcement": {**RACE["announcement"], "url": "https://example.com/strict"}}
            manifest.write_text(json.dumps({"year": 2026, "events": [local]}))
            (root / "fixture_2026.disclosure.html").write_text(
                "<h1>2026示例马拉松</h1><p>本届破三人数达26人。</p>")
            snapshot = root / "snapshot.json"
            run(manifest, snapshot, False, NOW, announcement_dir=root)
            result = json.loads(snapshot.read_text())["events"][0]
            self.assertEqual(result["disclosed_count"], 26)
            self.assertEqual(result["source_url"], RACE["announcement"]["url"])

    def test_downloads_a_reviewed_official_csv_and_rejects_changed_bytes(self):
        official = b"bib,net_time,status\n1,2:59:59,FINISHED\n2,3:00:00,FINISHED\n"
        config = {"url": "https://example.com/results", "download_url": "https://timekeeper.example.com/full.csv",
                  "timing_basis": "NET_TIME", "coverage": "ALL_RESULT_ROWS", "expected_rows": 2,
                  "sha256": sha256(official).hexdigest()}
        manifest = {"year": 2026, "events": [{**RACE, "results": config}]}
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with patch("collect_marathon_sub3.fetch_results", create=True, return_value=official) as remote:
                row = collect(manifest, root, NOW, fetch=lambda _: "")["events"][0]
            self.assertEqual(row["verified_count"], 1)
            remote.assert_called_once_with(config["download_url"])
            with patch("collect_marathon_sub3.fetch_results", return_value=official + b"\n"):
                changed = collect(manifest, root, NOW, fetch=lambda _: "")["events"][0]
            self.assertNotIn("verified_count", changed)

    def test_a_failed_live_report_can_use_the_matching_archived_page(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({"year": 2026, "events": [RACE]}))
            (root / "fixture_2026.html").write_text("<h1>2026示例马拉松</h1><p>本届破三人数达26人。</p>")
            output = root / "snapshot.json"
            with patch("collect_marathon_sub3.fetch_html", side_effect=IncompleteRead(b"partial")) as remote:
                run(manifest, output, False, NOW, announcement_fallback_dir=root)
            remote.assert_called_once_with(RACE["announcement"]["url"])
            self.assertEqual(json.loads(output.read_text())["events"][0]["disclosed_count"], 26)

    def test_announcement_does_not_capture_the_preceding_chinese_comma(self):
        selector = read_json(PILOT)["events"][0]["announcement"]
        html = f"<h1>{selector['title']}</h1><p>大家的托举下，1234位选手成功&ldquo;破三&rdquo;。</p>"
        self.assertEqual(disclosure(html, selector), 1234)

    def test_ineligible_events_are_excluded_and_wrong_article_stays_unavailable(self):
        races = [RACE, {**RACE, "event_id": "half_2026", "distance": "HALF_MARATHON"},
                 {**RACE, "event_id": "class_c_2026", "cert_level": "C"},
                 {**RACE, "event_id": "overseas_2026", "country": "JP"},
                 {**RACE, "event_id": "hk_2026", "province": "香港特别行政区"}]
        with tempfile.TemporaryDirectory() as temp:
            snapshot = collect({"year": 2026, "events": races}, Path(temp), NOW,
                               fetch=lambda _: "<h1>2025示例马拉松</h1><p>破三人数达29人</p>")
        self.assertEqual(len(snapshot["events"]), 1)
        self.assertEqual(snapshot["events"][0]["status"], "UNAVAILABLE")
        self.assertEqual(snapshot["as_of"], str(date(2026, 11, 26)))

    def test_failed_refresh_preserves_previous_disclosure_but_marks_it_stale(self):
        html = "<h1>2026示例马拉松</h1><p>破三人数达26人。</p>"
        manifest = {"year": 2026, "events": [RACE]}
        with tempfile.TemporaryDirectory() as temp:
            prior = collect(manifest, Path(temp), NOW, fetch=lambda _: html)
            def unavailable(_):
                raise TimeoutError()
            result = collect(manifest, Path(temp), NOW, prior=prior, fetch=unavailable)
        self.assertTrue(result["events"][0]["stale"])
        self.assertEqual(result["events"][0]["disclosed_count"], 26)

    def test_does_not_shrink_an_imported_catalog_when_pdf_argument_is_omitted(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "manifest.json"
            source.write_text(json.dumps({"year": 2026, "directory": {"expected_rows": 3}, "events": [RACE]}))
            earlier = {"year": 2026, "events": [{"event_id": "fixture_2026"}, {"event_id": "extra_2026"}]}
            output = root / "snapshot.json"
            output.write_text(json.dumps(earlier))
            with self.assertRaisesRegex(ValueError, "catalog PDF"):
                run(source, output, True, NOW)
            self.assertEqual(json.loads(output.read_text()), earlier)

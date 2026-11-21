import json
import os
import tempfile
import unittest
from datetime import date, datetime, timezone
from pathlib import Path

from sync_marathon_events import sync, validate_snapshot


FIXTURE = Path(__file__).resolve().parents[1] / "src/test/resources/marathon/synthetic_calendar.json"
TODAY = date(2026, 11, 21)
NOW = datetime(2026, 11, 21, 5, 30, tzinfo=timezone.utc)
LIST_URL = "https://calendar.example/"
EVENT_URL = "https://calendar.example/events/123"


def listing(day=6):
    return f"""<main><a href="/events/123"><h3>2026示例半程马拉松</h3>
                <span>2026年12月{day:02}日</span><span>示例省・示例市</span></a></main>"""


def details(day=6, country="中国大陆"):
    return f"""<main><h1><a href="/organizer">2026示例半程马拉松</a></h1>
                <p>比赛日期</p><p>2026年12月{day:02}日</p>
                <p>比赛地点</p><p>示例省・示例市</p>
                <p>国家/地区</p><p>{country}</p></main>"""


class SyncMarathonEventsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        base = Path(self.directory.name)
        self.seed = base / "seed.json"
        seed = json.loads(FIXTURE.read_text(encoding="utf-8"))
        seed["events"][0]["status"] = "PLANNED"
        seed["events"][0]["source"]["url"] = EVENT_URL
        self.seed.write_text(json.dumps(seed), encoding="utf-8")
        self.output = base / "snapshot.json"
        self.config = base / "source.json"
        self.config.write_text(json.dumps({
            "seed": self.seed.name,
            "calendar_url": LIST_URL,
            "organization": "示例公开赛历",
            "max_pages": 1,
            "matches": [{
                "series_id": "fixture_half",
                "name_pattern": r"(?P<year>\d{4})示例半程马拉松",
                "city": "示例市",
                "province": "示例省",
                "distances": ["HALF_MARATHON"],
            }],
        }), encoding="utf-8")

    def sync(self, cards, page, dry_run=False):
        def fetch(url):
            if url == LIST_URL:
                return cards
            if url == EVENT_URL and page is not None:
                return page
            raise OSError("Synthetic source unavailable")

        return sync(self.config, self.output, TODAY, NOW, dry_run, fetch)

    def test_shared_fixture_and_atomic_idempotent_publish(self):
        self.assertEqual(validate_snapshot(json.loads(FIXTURE.read_text(encoding="utf-8")))["schema_version"], 1)
        self.sync(listing(), details(), dry_run=True)
        self.assertFalse(self.output.exists())
        self.sync(listing(), details())
        before = self.output.read_bytes()
        self.assertEqual(self.output.stat().st_mode & 0o777, 0o644)
        self.sync(listing(), details())
        self.assertEqual(self.output.read_bytes(), before)

        self.sync(listing(9), details(9))
        events = json.loads(self.output.read_text(encoding="utf-8"))["events"]
        self.assertEqual([(event["event_id"], event["race_date"]) for event in events],
                         [("fixture_half_2026", "2026-12-09")])
        self.assertEqual(events[0]["source"]["verified_at"], "2026-11-21T05:30:00Z")

    def test_failures_leave_previous_bytes_and_source_timestamp(self):
        self.sync(listing(), details())
        before = self.output.read_bytes()
        for cards, page in [("<main></main>", details()), (listing(), None), (listing(8), details(9)),
                            (listing(), details(country="示例外国")),
                            (listing(), details().replace("示例省・示例市", "其他省・其他市"))]:
            with self.assertRaises(ValueError):
                self.sync(cards, page)
            self.assertEqual(self.output.read_bytes(), before)

    def test_calendar_conflict_retains_verified_official_date(self):
        self.output.write_bytes(FIXTURE.read_bytes())
        before = self.output.read_bytes()
        self.sync(listing(9), details(9))
        self.assertEqual(json.loads(self.output.read_text(encoding="utf-8"))["events"],
                         json.loads(before)["events"])

    def test_new_official_schedule_takes_priority_over_later_verified_calendar(self):
        self.sync(listing(), details())
        updated = json.loads(FIXTURE.read_text(encoding="utf-8"))
        updated["events"][0]["race_date"] = "2026-12-12"
        self.seed.write_text(json.dumps(updated), encoding="utf-8")

        self.sync(listing(), details())
        event = json.loads(self.output.read_text(encoding="utf-8"))["events"][0]
        self.assertEqual(event["status"], "CONFIRMED")
        self.assertEqual(event["race_date"], "2026-12-12")

    def test_invalid_candidate_never_replaces_existing_snapshot(self):
        self.sync(listing(), details())
        before = self.output.read_bytes()
        config = json.loads(self.config.read_text())
        config["matches"][0]["distances"] = ["MARATHON", "MARATHON"]
        self.config.write_text(json.dumps(config), encoding="utf-8")
        with self.assertRaises(ValueError):
            self.sync(listing(), details())
        self.assertEqual(self.output.read_bytes(), before)


if __name__ == "__main__":
    unittest.main()

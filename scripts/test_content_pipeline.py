from pathlib import Path
from struct import pack
from tempfile import TemporaryDirectory
import unittest

from content_pipeline.training import Interval, summarize, read_training
from content_pipeline.races import race_facts
from content_pipeline.preview import export_preview

SOURCE = {"url": "https://example.com/official", "organization": "示例组委会"}
CALENDAR = {"series": [{"series_id": "fiction_marathon", "name": "示例马拉松"}]}
PODIUMS = [{
    "event_id": "fiction_marathon_2026", "series_id": "fiction_marathon",
    "race_date": "2026-11-22", "distance": "MARATHON",
    "groups": [{"sub_category": "OVERALL", "category": "WOMEN", "source": SOURCE,
                "results": [{"athlete_name": "示例跑者", "rank": 1, "finish_time": "02:30:00"}]}],
}]


class ContentPipelineTest(unittest.TestCase):
    def test_equal_duration_halves_and_distance_splits(self):
        result = summarize([Interval(300, 1000, 150), Interval(300, 800, 160),
                            Interval(300, 1000, 150), Interval(300, 800, 160)])
        self.assertEqual(result["distance_m"], 3600)
        self.assertEqual(result["drift_pct"], 0.0)
        self.assertEqual([r["pace_s"] for r in result["kilometers"]], [300, 360, 315])
        self.assertEqual(result["pace_fade_pct"], 5.0)

    def test_heart_rate_gap_does_not_produce_an_efficiency_value(self):
        result = summarize([Interval(350, 1000, 145), Interval(350, 1000, None)])
        self.assertIsNone(result["efficiency"])
        self.assertIsNone(result["drift_pct"])

    def test_json_and_gpx_parser_emit_only_distance_time_and_heart_rate(self):
        with TemporaryDirectory() as temp:
            path = Path(temp) / "sample.json"
            path.write_text('{"intervals":[{"duration_s":300,"distance_m":1000,"heart_rate":150}]}')
            self.assertEqual(read_training(path), [Interval(300, 1000, 150)])
            gpx = path.with_suffix(".gpx")
            gpx.write_text('''<gpx version="1.1" creator="fixture"
                xmlns="http://www.topografix.com/GPX/1/1" xmlns:tr="urn:test">
                <trk><trkseg><trkpt lat="10" lon="40"><time>2026-01-01T12:00:00Z</time></trkpt>
                <trkpt lat="10.001" lon="40"><time>2026-01-01T12:01:00Z</time>
                <extensions><tr:TrackPointExtension><tr:hr>150</tr:hr></tr:TrackPointExtension></extensions>
                </trkpt></trkseg></trk></gpx>''')
            try:
                intervals = read_training(gpx)
            except ModuleNotFoundError:
                self.skipTest("Optional gpxpy is not installed")
            self.assertEqual(intervals[0].duration_s, 60)
            self.assertEqual(intervals[0].heart_rate, 150)
            self.assertAlmostEqual(intervals[0].distance_m, 111.3, delta=1.0)

    def test_reads_two_synthetic_fit_records(self):
        try:
            from fitparse.base import Crc
        except ModuleNotFoundError:
            self.skipTest("Optional fitparse is not installed")
        definition = bytes([0x40, 0, 0]) + pack("<H", 20) + bytes([3, 253, 4, 0x86, 5, 4, 0x86, 3, 1, 2])
        start = 1_120_000_000
        records = b"\0" + pack("<IIB", start, 0, 140) + b"\0" + pack("<IIB", start + 300, 100000, 151)
        payload = definition + records
        file = pack("<BBHI4s", 12, 0x10, 100, len(payload), b".FIT") + payload
        with TemporaryDirectory() as temp:
            path = Path(temp) / "run.fit"
            path.write_bytes(file + pack("<H", Crc.calculate(file)))
            self.assertEqual(read_training(path), [Interval(300, 1000, 151)])

    def test_race_preserves_verified_results_and_sources(self):
        facts = race_facts(CALENDAR, PODIUMS, athlete="示例跑者")
        self.assertEqual(facts["results"][0]["pace_s"], 213)
        self.assertNotIn("pb", facts["results"][0])
        with TemporaryDirectory() as temp:
            path = export_preview("race_news_athlete", facts, Path(temp))
            self.assertIn(SOURCE["url"], (path / "preview.html").read_text())
            self.assertEqual(path, export_preview("race_news_athlete", facts, Path(temp)))

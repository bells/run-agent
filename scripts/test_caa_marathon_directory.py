import unittest

from caa_marathon_directory import merge_directory, parse_tables

SOURCE = "https://example.com/directory.pdf"
HEADER = ["序号", "省份", "赛事名称", "比赛时间", "主 办 单 位", "赛事设项", "赛事\n类别"]
ROWS = [
    ["1", "江苏", "2026示例城市\n马拉松", "3月22日", "主办方", "全程/半程", "A"],
    ["2", "上海", "2026第二届马拉松", "12月", "主办方", "全程", "B"],
    ["3", "北京", "2026示例半程马拉松", "待定", "主办方", "半程", "A"],
    ["4", "福建", "2026示例海滨马拉松", "4月", "主办方", "全程", "C"],
]


class CaaMarathonDirectoryTest(unittest.TestCase):
    def test_preserves_month_precision_and_matches_overrides_by_directory_row(self):
        manifest = {"year": 2026, "catalog_source": SOURCE,
                    "events": [{"directory_row": 1, "event_id": "fixture_2026", "province": "江苏",
                                "cert_level": "A", "state": "COMPLETED", "race_date": "2026-03-22"}]}
        candidates = parse_tables([[HEADER, *ROWS]], 2026, SOURCE, 4)
        events = merge_directory(manifest, candidates)["events"]
        self.assertEqual([row["cert_level"] for row in events], ["A", "B"])
        self.assertEqual(events[0]["event_id"], "fixture_2026")
        self.assertEqual(events[0]["state"], "COMPLETED")
        self.assertEqual(events[1]["date_hint"], "12月")
        self.assertIsNone(events[1]["race_date"])
        self.assertEqual(events[1]["state"], "PLANNED")

    def test_rejects_missing_and_out_of_order_ordinal(self):
        with self.assertRaises(ValueError):
            parse_tables([[HEADER, ROWS[0], ROWS[2], ROWS[3]]], 2026, SOURCE, 4)

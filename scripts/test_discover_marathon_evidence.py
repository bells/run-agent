from datetime import datetime, timezone
import unittest

from content_pipeline.discovery import discover


class DiscoveryTest(unittest.TestCase):
    def test_honors_source_domains_and_retains_only_candidate_links(self):
        def search(parameters):
            self.assertEqual(parameters["start_date"], "2026-11-24")
            return {"results": [
                {"url": "https://results.example.com/report", "title": "官方战报", "published_date": "2026-11-26",
                 "content": "third-party page text"},
                {"url": "https://example.com.evil.test/report", "title": "伪造的域名"},
                {"url": "https://example.com/old", "title": "历史战报", "published_date": "2026-11-01"},
            ]}
        results = discover("示例马拉松", ["example.com"], 72,
                           datetime(2026, 11, 27, 5, tzinfo=timezone.utc), search)
        self.assertEqual(results, [{"url": "https://results.example.com/report", "title": "官方战报",
                                    "published_date": "2026-11-26", "status": "CANDIDATE"}])

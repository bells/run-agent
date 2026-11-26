from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from content_pipeline.wechat_client import WeChatClient, WeChatError

PNG = b"\x89PNG\r\n\x1a\nexample"


class WeChatClientTest(unittest.TestCase):
    def test_single_draft_replaces_local_images_and_reuses_receipt(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)
            cover = root / "cover.png"
            chart = root / "chart.png"
            cover.write_bytes(PNG)
            chart.write_bytes(PNG)
            calls = []
            def post(path, token=None, json_body=None, media=None):
                calls.append((path, token, json_body, media))
                return {"stable_token": {"access_token": "token", "expires_in": 7200},
                        "media/uploadimg": {"url": "https://mmbiz.qpic.cn/figure"},
                        "material/add_material": {"media_id": "cover-id"},
                        "draft/add": {"media_id": "draft-id"}}[path]

            client = WeChatClient("appid", "secret", root / "cache", post=post)
            body = '<p><img src="chart.png" /></p>'
            self.assertEqual(client.add_draft("示例", body, root, cover), "draft-id")
            self.assertEqual(client.add_draft("示例", body, root, cover), "draft-id")
            self.assertEqual([call[0] for call in calls],
                             ["stable_token", "media/uploadimg", "material/add_material", "draft/add"])
            article = calls[-1][2]["articles"][0]
            self.assertEqual(article["thumb_media_id"], "cover-id")
            self.assertIn("https://mmbiz.qpic.cn/figure", article["content"])
            self.assertNotIn("chart.png", article["content"])

    def test_indeterminate_write_blocks_a_duplicate_attempt(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)
            image = root / "cover.png"
            image.write_bytes(PNG)
            calls = []
            def post(path, token=None, json_body=None, media=None):
                calls.append(path)
                if path == "stable_token":
                    return {"access_token": "token", "expires_in": 7200}
                if path == "material/add_material":
                    return {"media_id": "cover"}
                raise WeChatError("Unexpected connection error")
            client = WeChatClient("appid", "secret", root / "cache", post=post)
            with self.assertRaises(WeChatError):
                client.add_draft("示例", "<p>正文</p>", root, image)
            with self.assertRaisesRegex(WeChatError, "uncertain"):
                client.add_draft("示例", "<p>正文</p>", root, image)
            self.assertEqual(calls.count("draft/add"), 1)

    def test_rejects_external_images_before_creating_remote_materials(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)
            image = root / "cover.png"
            image.write_bytes(PNG)
            def post(path, **_):
                self.fail("Unsafe input reached API")
            client = WeChatClient("appid", "secret", root / "cache", post=post)
            with self.assertRaises(ValueError):
                client.add_draft("示例", '<img src="https://example.com/news.png">', root, image)

    def test_explicit_draft_rejection_reuses_the_already_uploaded_cover(self):
        with TemporaryDirectory() as temp:
            root = Path(temp)
            cover = root / "cover.png"
            cover.write_bytes(PNG)
            calls = []
            def post(path, **_):
                calls.append(path)
                if path == "stable_token":
                    return {"access_token": "token", "expires_in": 7200}
                if path == "material/add_material":
                    return {"media_id": "cover-id"}
                if calls.count("draft/add") == 1:
                    return {"errcode": 45003}
                return {"media_id": "draft-id"}
            client = WeChatClient("appid", "secret", root / "cache", post=post)
            with self.assertRaises(WeChatError):
                client.add_draft("示例", "<p>正文</p>", root, cover)
            self.assertEqual(client.add_draft("示例", "<p>正文</p>", root, cover), "draft-id")
            self.assertEqual(calls.count("material/add_material"), 1)

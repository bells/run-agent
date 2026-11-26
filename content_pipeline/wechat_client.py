"""Upload local materials and add a single Official Account draft."""

from contextlib import contextmanager
import fcntl
from hashlib import sha256
from html import escape
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
from tempfile import NamedTemporaryFile
import time
from urllib.parse import urlencode, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import uuid4

API = "https://api.weixin.qq.com/cgi-bin/"
PATHS = {"stable_token", "media/uploadimg", "material/add_material", "draft/add"}
TOKEN_ERRORS = {40001, 40014, 42001}
MEDIA_FILE = re.compile(r"[a-zA-Z0-9_.-]+\.(?:png|jpe?g)", re.I)
MAX_RESPONSE = 1024 * 1024


class WeChatError(RuntimeError):
    pass


class WeChatRejected(WeChatError):
    def __init__(self, api_code):
        super().__init__(f"WeChat rejected the request (errcode {api_code})")
        self.api_code = api_code


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def api_post(path, token=None, json_body=None, media=None):
    if path not in PATHS:
        raise ValueError("Unsupported WeChat API method")
    params = {"access_token": token} if token else {}
    if path == "material/add_material":
        params["type"] = "image"
    url = API + path + ("?" + urlencode(params) if params else "")
    if media is not None:
        file_name, mime, content = media
        boundary = uuid4().hex
        headers = (f'--{boundary}\r\nContent-Disposition: form-data; name="media"; filename="{file_name}"'
                   f'\r\nContent-Type: {mime}\r\n\r\n').encode()
        data = headers + content + f"\r\n--{boundary}--\r\n".encode()
        content_type = f"multipart/form-data; boundary={boundary}"
    else:
        data = json.dumps(json_body, ensure_ascii=False).encode()
        content_type = "application/json"
    try:
        request = Request(url, data=data, headers={"Content-Type": content_type}, method="POST")
        with build_opener(NoRedirects()).open(request, timeout=20) as response:
            data = response.read(MAX_RESPONSE + 1)
        if len(data) > MAX_RESPONSE:
            raise WeChatError("Unexpected WeChat response size")
        result = json.loads(data)
    except (OSError, ValueError):
        # The transport URL can contain an access token.
        raise WeChatError(f"WeChat transport failed for {path}") from None
    if not isinstance(result, dict):
        raise WeChatError("Invalid WeChat response")
    return result


@contextmanager
def cache_lock(file):
    file.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    with file.with_suffix(".lock").open("ab") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        yield


def save_json(file, value):
    temporary = None
    try:
        with NamedTemporaryFile(mode="w", encoding="utf-8", dir=file.parent, delete=False) as handle:
            temporary = Path(handle.name)
            json.dump(value, handle, ensure_ascii=False)
            handle.flush()
            os.fsync(handle.fileno())
        temporary.replace(file)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


class LocalImages(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.items = {}

    def handle_starttag(self, tag, attrs):
        if tag != "img":
            return
        sources = [value for key, value in attrs if key == "src"]
        if (len(sources) != 1 or not sources[0] or not MEDIA_FILE.fullmatch(sources[0])
                or any(key == "srcset" or key.startswith("on") for key, _ in attrs)):
            raise ValueError("Article images must be local PNG or JPEG files")
        self.items[self.get_starttag_text()] = sources[0]


def local_media(path, limit):
    if path.suffix.lower() not in {".jpeg", ".jpg", ".png"}:
        raise ValueError("Expected a local PNG or JPEG")
    with path.open("rb") as source:
        content = source.read(limit + 1)
    if len(content) > limit:
        raise ValueError("Image exceeds the supported size limit")
    is_png = content.startswith(b"\x89PNG\r\n\x1a\n")
    is_jpeg = content.startswith(b"\xff\xd8\xff")
    if not (is_png or is_jpeg):
        raise ValueError("Invalid image signature")
    return ("image.png", "image/png", content) if is_png else ("image.jpg", "image/jpeg", content)


def uploaded_url(response):
    url = response.get("url")
    if not isinstance(url, str):
        raise WeChatError("Upload response did not contain an image URL")
    parts = urlsplit(url)
    if (parts.scheme not in {"https", "http"} or parts.hostname not in {"mmbiz.qpic.cn", "mmbiz.qlogo.cn"}
            or parts.username is not None or parts.password is not None):
        raise WeChatError("Upload response provided an unexpected image URL")
    return parts._replace(scheme="https").geturl()


def media_id(response):
    value = response.get("media_id")
    if not isinstance(value, str) or not value.strip():
        raise WeChatError("Expected a WeChat media ID")
    return value


class WeChatClient:
    def __init__(self, app_id, app_secret, cache, *, post=api_post, now=time.time):
        if not app_id or not app_secret:
            raise ValueError("Set WECHAT_APP_ID and WECHAT_APP_SECRET")
        self.app_id = app_id
        self.app_secret = app_secret
        self.root = cache / sha256(app_id.encode()).hexdigest()[:16]
        self.post = post
        self.now = now

    def checked_post(self, path, **arguments):
        result = self.post(path, **arguments)
        code = result.get("errcode", 0)
        if type(code) is not int:
            raise WeChatError("Unexpected WeChat status")
        if code:
            raise WeChatRejected(code)
        return result

    def token(self, invalid_token=None):
        file = self.root / "token.json"
        with cache_lock(file):
            try:
                cached = json.loads(file.read_text())
                cached_token = cached["token"]
                if isinstance(cached_token, str) and cached_token and cached_token != invalid_token:
                    if cached["expires_at"] > self.now() + 120:
                        return cached_token
            except (OSError, ValueError, KeyError, TypeError):
                pass
            result = self.checked_post("stable_token", json_body={
                "grant_type": "client_credential", "appid": self.app_id, "secret": self.app_secret,
                "force_refresh": invalid_token is not None})
            token, lifetime = result.get("access_token"), result.get("expires_in")
            if not isinstance(token, str) or not token or type(lifetime) is not int or lifetime <= 0:
                raise WeChatError("Cannot obtain a WeChat access token")
            save_json(file, {"token": token, "expires_at": self.now() + lifetime})
            return token

    def authorized_post(self, path, **kwargs):
        token = self.token()
        try:
            return self.checked_post(path, token=token, **kwargs)
        except WeChatRejected as error:
            if error.api_code not in TOKEN_ERRORS:
                raise
            return self.checked_post(path, token=self.token(invalid_token=token), **kwargs)

    def cached_material(self, path, media, read_result):
        digest = sha256(media[2]).hexdigest()
        role = "cover" if path == "material/add_material" else "image"
        file = self.root / f"{role}-{digest}.json"
        with cache_lock(file):
            if file.exists():
                return read_result(json.loads(file.read_text()))
            response = self.authorized_post(path, media=media)
            value = read_result(response)
            save_json(file, {"media_id" if role == "cover" else "url": value})
            return value

    def add_draft(self, title, content, asset_dir, cover):
        if not title.strip() or len(title) > 64 or not content.strip() or len(content.encode()) > MAX_RESPONSE:
            raise ValueError("Invalid draft title or body length")
        parser = LocalImages()
        parser.feed(content)
        parser.close()
        media = {}
        for name in dict.fromkeys(parser.items.values()):
            path = (asset_dir / name).resolve()
            if path.parent != asset_dir.resolve():
                raise ValueError("Article images must be inside the draft bundle")
            media[name] = local_media(path, 1024 * 1024)
        cover_media = local_media(cover, 2 * 1024 * 1024)
        checksum = sha256(json.dumps([title, content, sorted(media)], ensure_ascii=False).encode())
        for name in sorted(media):
            checksum.update(sha256(media[name][2]).digest())
        checksum.update(sha256(cover_media[2]).digest())
        receipt_file = self.root / f"draft-{checksum.hexdigest()}.json"
        with cache_lock(receipt_file):
            if receipt_file.exists():
                receipt = json.loads(receipt_file.read_text())
                if receipt.get("media_id"):
                    return receipt["media_id"]
                raise WeChatError(f"Draft delivery uncertain. Check the WeChat Draft Box before retrying ({receipt_file.name})")
            urls = {name: self.cached_material("media/uploadimg", part, uploaded_url)
                    for name, part in media.items()}
            for tag, name in parser.items.items():
                content = content.replace(tag, f'<img src="{escape(urls[name], quote=True)}" '
                                          'style="width:100%;height:auto;display:block;" />')
            cover_id = self.cached_material("material/add_material", cover_media, media_id)
            article = {"title": title, "content": content, "thumb_media_id": cover_id}
            save_json(receipt_file, {"status": "pending"})
            try:
                result = self.authorized_post("draft/add", json_body={"articles": [article]})
            except WeChatRejected:
                receipt_file.unlink()
                raise
            result_id = media_id(result)
            save_json(receipt_file, {"media_id": result_id})
            return result_id

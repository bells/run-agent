#!/usr/bin/env python3
"""Refresh RunAgent's public marathon calendar using Python 3.10+ on Unix."""

import argparse
import calendar
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import date, datetime, timedelta, timezone
import fcntl
from html.parser import HTMLParser
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import unicodedata
from urllib.parse import urljoin, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from zoneinfo import ZoneInfo

MAX_BYTES = 2 * 1024 * 1024
DISTANCES = {"MARATHON", "HALF_MARATHON"}
STATUSES = {"PLANNED", "CONFIRMED", "POSTPONED", "CANCELLED", "COMPLETED"}
SLUG = re.compile(r"[a-z0-9][a-z0-9_-]{0,79}")
HOST = re.compile(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")
CHINESE_DATE = re.compile(r"(\d{4})年(\d{1,2})月(\d{1,2})日")
SHANGHAI = ZoneInfo("Asia/Shanghai")
CONFIG_PATH = Path(__file__).with_name("marathon_sources.json")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def string(value, limit):
    return (isinstance(value, str) and bool(value.strip()) and len(value) <= limit
            and not any(ord(char) < 32 or 127 <= ord(char) < 160 for char in value))


def optional_text(value, limit):
    return value is None or string(value, limit)


def stamp(value):
    require(isinstance(value, str) and re.fullmatch(
        r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})", value
    ), "Invalid timestamp")
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def iso_date(value):
    require(isinstance(value, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}", value), "Invalid date")
    return date.fromisoformat(value)


def https_url(value):
    require(string(value, 2048), "Invalid source URL")
    url = urlsplit(value)
    require(url.scheme == "https" and url.hostname and url.username is None and url.password is None
            and url.port in (None, 443), "Invalid source URL")
    return url


def valid_distances(distances):
    return (isinstance(distances, list) and 1 <= len(distances) <= 2
            and set(distances) <= DISTANCES and len(set(distances)) == len(distances))


def validate_snapshot(snapshot):
    require(type(snapshot["schema_version"]) is int and snapshot["schema_version"] == 1, "Unsupported schema version")
    stamp(snapshot["published_at"])
    iso_date(snapshot["coverage_through"])
    require(isinstance(snapshot["series"], list) and 1 <= len(snapshot["series"]) <= 1000, "Invalid series list")
    require(isinstance(snapshot["events"], list) and 1 <= len(snapshot["events"]) <= 10000, "Invalid edition list")
    series_ids = set()
    for item in snapshot["series"]:
        slug = item["series_id"]
        require(isinstance(slug, str) and SLUG.fullmatch(slug) and slug not in series_ids
                and string(item["name"], 160), "Invalid or duplicate series")
        series_ids.add(slug)
        for field, count, length in [("aliases", 40, 160), ("tags", 20, 80), ("official_hosts", 30, 253)]:
            values = item.get(field)
            require(values is None or (isinstance(values, list) and len(values) <= count
                                       and all(string(value, length) for value in values)), "Invalid series metadata")
        require(all(HOST.fullmatch(host) and ".." not in host for host in item.get("official_hosts") or []),
                "Invalid official host")
    event_ids = set()
    for item in snapshot["events"]:
        year, slug, key = item["year"], item["series_id"], item["event_id"]
        require(type(year) is int and 1900 <= year <= 9999 and slug in series_ids
                and key == f"{slug}_{year}" and key not in event_ids, "Invalid or duplicate edition")
        event_ids.add(key)
        race_date = iso_date(item["race_date"])
        zone = item["timezone"]
        require(string(zone, 80) and string(item["city"], 80) and string(item["country"], 80)
                and optional_text(item.get("province"), 80) and optional_text(item.get("cert_level"), 80),
                "Invalid edition metadata")
        ZoneInfo(zone)
        distances = item["distances"]
        require(valid_distances(distances), "Invalid distances")
        starts = item.get("start_times")
        require(starts is None or (isinstance(starts, dict) and set(starts) <= set(distances)
                                  and all(re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d{1,9})?)?", time)
                                          for time in starts.values())), "Invalid start times")
        deadline = item.get("reg_deadline")
        require(deadline is None or iso_date(deadline) <= race_date, "Invalid registration deadline")
        require(item["status"] in STATUSES, "Invalid edition status")
        source = item["source"]
        https_url(source["url"])
        require(string(source["organization"], 160), "Missing source organization")
        stamp(source["verified_at"])
    return snapshot


def reject_duplicate_properties(entries):
    result = {}
    for key, value in entries:
        require(key not in result, "Duplicate JSON key")
        result[key] = value
    return result


def read_json(path):
    with path.open("rb") as source:
        data = source.read(MAX_BYTES + 1)
    require(len(data) <= MAX_BYTES, "JSON input exceeds size limit")
    return json.loads(data, object_pairs_hook=reject_duplicate_properties)


class CalendarHtml(HTMLParser):
    def __init__(self, html):
        super().__init__()
        self.active = {}
        self.elements = []
        self.feed(html)
        self.close()

    def handle_starttag(self, tag, attrs):
        if tag in {"h1", "h3", "p", "a"}:
            self.active[tag] = (dict(attrs).get("href"), [])

    def handle_data(self, data):
        for _, texts in self.active.values():
            texts.append(data.strip())

    def handle_endtag(self, tag):
        if tag in self.active:
            href, texts = self.active.pop(tag)
            text = " ".join(part for part in texts if part)
            if tag == "h3" and "a" in self.active:
                anchor, _ = self.active["a"]
                self.elements.append(("title", anchor, text))
            self.elements.append((tag, href, text))


def chinese_date(value):
    match = CHINESE_DATE.search(value)
    require(match is not None, "Missing calendar date")
    return date(*map(int, match.groups()))


@dataclass(frozen=True)
class Card:
    url: str
    match: dict
    year: int
    race_date: date


def match_title(title, matches):
    name = re.sub(r"\s+", "", unicodedata.normalize("NFKC", title))
    for rule in matches:
        found = re.fullmatch(rule["name_pattern"], name)
        if found:
            return rule, int(found["year"])
    return None


def list_cards(html, url, matches, today, through):
    cards = []
    seen = 0
    elements = CalendarHtml(html).elements
    titles = {href: text for tag, href, text in elements if tag == "title"}
    for tag, href, contents in elements:
        if tag != "a" or href not in titles:
            continue
        detail = urljoin(url, href)
        parts = urlsplit(detail)
        if (parts.scheme, parts.netloc) != ("https", urlsplit(url).netloc) or parts.query or parts.fragment:
            continue
        if not re.fullmatch(r"/events/\d+", parts.path):
            continue
        dated = CHINESE_DATE.search(contents)
        if not dated:
            continue
        seen += 1
        matched = match_title(titles[href], matches)
        if matched:
            race_date = chinese_date(dated[0])
            if today <= race_date <= through:
                rule, year = matched
                cards.append(Card(detail, rule, year, race_date))
    require(seen > 0, "No dated cards in calendar page")
    return cards


def parse_detail(html, card, organization, now):
    elements = CalendarHtml(html).elements
    names = [text for tag, _, text in elements if tag == "h1"]
    paragraphs = [text for tag, _, text in elements if tag == "p"]
    matched = match_title(names[0], [card.match]) if len(names) == 1 else None
    require(matched is not None and matched[1] == card.year, "Calendar detail title disagrees with list")
    fields = dict(zip(paragraphs, paragraphs[1:]))
    require(chinese_date(fields.get("比赛日期", "")) == card.race_date, "Calendar detail date disagrees with list")
    rule = card.match
    location = re.split(r"[・·]", fields.get("比赛地点", ""), maxsplit=2)[:2]
    require(fields.get("国家/地区") in {"中国大陆", "中国"}
            and any(part.strip().startswith(rule["city"]) for part in location)
            and (not rule.get("province") or location[0].strip().startswith(rule["province"])),
            "Calendar detail location disagrees with the configured series")
    slug = rule["series_id"]
    return {
        "event_id": f"{slug}_{card.year}", "series_id": slug, "year": card.year,
        "race_date": card.race_date.isoformat(), "timezone": "Asia/Shanghai",
        "city": rule["city"], "province": rule.get("province"), "country": "中国",
        "distances": rule["distances"], "start_times": {}, "reg_deadline": None,
        "cert_level": None, "status": "PLANNED",
        "source": {"url": card.url, "organization": organization, "verified_at": utc_timestamp(now)},
    }


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        return None


def fetch_html(url):
    https_url(url)
    opener = build_opener(NoRedirects())
    request = Request(url, headers={"User-Agent": "RunAgent-MarathonCalendar/1.0", "Accept": "text/html"})
    with opener.open(request, timeout=8) as response:
        require(response.headers.get_content_type() == "text/html", "Unexpected calendar content type")
        body = response.read(MAX_BYTES + 1)
        require(len(body) <= MAX_BYTES, "Calendar response exceeds size limit")
        return body.decode(response.headers.get_content_charset() or "utf-8")


def three_month_end(today):
    year, month_zero = divmod(today.year * 12 + today.month - 1 + 3, 12)
    month = month_zero + 1
    return date(year, month, min(today.day, calendar.monthrange(year, month)[1])) - timedelta(days=1)


def utc_timestamp(now):
    return now.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def warn(message):
    print(f"Warning: {message}", file=sys.stderr)


def collect(config, today, now, fetch):
    url = config["calendar_url"]
    parsed = https_url(url)
    require(not parsed.query and not parsed.fragment, "Calendar list URL must be a base page")
    max_pages = config["max_pages"]
    require(type(max_pages) is int and 1 <= max_pages <= 12, "Invalid calendar page cap")
    require(string(config["organization"], 160), "Invalid calendar organization")
    through = three_month_end(today)
    pages = [url] + [f"{url}?page={number}" for number in range(2, max_pages + 1)]
    with ThreadPoolExecutor(max_workers=4) as pool:
        groups = pool.map(lambda page: list_cards(fetch(page), url, config["matches"], today, through), pages)
        cards = {card.url: card for group in groups for card in group}
        require(0 < len(cards) <= 60, "Empty or excessive selected calendar results")

        def detail(card):
            return parse_detail(fetch(card.url), card, config["organization"], now)

        futures = {pool.submit(detail, card): card for card in cards.values()}
        events = []
        for future, card in futures.items():
            try:
                events.append(future.result())
            except (OSError, ValueError, LookupError) as error:
                warn(f"Detail unavailable for {card.match['series_id']}_{card.year} "
                     f"({type(error).__name__}); retaining previous entry")
    require(events, "No selected calendar details could be verified")
    return events, len(events) == len(cards)


def merge_events(previous, curated, candidates):
    events = {event["event_id"]: event for event in previous}
    for event in curated:
        key = event["event_id"]
        old = events.get(key)
        if old is None or old["status"] == "PLANNED" and event["status"] != "PLANNED":
            events[key] = event
            continue
        if stamp(event["source"]["verified_at"]) <= stamp(old["source"]["verified_at"]):
            continue
        if old["status"] != "PLANNED" and event["status"] == "PLANNED":
            continue
        if old["race_date"] != event["race_date"] and old["source"]["url"] != event["source"]["url"]:
            warn(f"Conflicting curated sources for {key}; retaining previous entry")
            continue
        events[key] = event

    by_id = {}
    for event in candidates:
        by_id.setdefault(event["event_id"], []).append(event)
    for key, variants in by_id.items():
        old = events.get(key)
        dates = {event["race_date"] for event in variants}
        if len(dates) > 1:
            warn(f"Conflicting calendar dates for {key}; retaining previous entry")
            continue
        matching_source = next((event for event in variants
                                if old is not None and event["source"]["url"] == old["source"]["url"]), None)
        new = matching_source or variants[0]
        if old is None:
            events[key] = new
        elif old["status"] == "PLANNED" and matching_source is not None:
            if old["race_date"] == new["race_date"]:
                events[key] = {**old, "source": new["source"]}
            else:
                events[key] = new
        elif old["race_date"] != new["race_date"]:
            warn(f"Calendar date conflicts with verified entry for {key}; retaining previous entry")
    return sorted(events.values(), key=lambda event: (event["race_date"], event["event_id"]))


@contextmanager
def write_lock(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    lock = path.with_name(f".{path.name}.lock")
    with lock.open("ab") as handle:
        fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield


def atomic_publish(path, data):
    staging = None
    try:
        with tempfile.NamedTemporaryFile(mode="wb", prefix=f".{path.name}.", dir=path.parent, delete=False) as tmp:
            staging = Path(tmp.name)
            os.fchmod(tmp.fileno(), 0o644)
            tmp.write(data)
            tmp.flush()
            os.fsync(tmp.fileno())
        os.replace(staging, path)
    finally:
        if staging is not None:
            staging.unlink(missing_ok=True)


def build_snapshot(config_path, output, today, now, fetch):
    config = read_json(config_path)
    seed = validate_snapshot(read_json(config_path.parent / config["seed"]))
    try:
        previous = validate_snapshot(read_json(output))
    except FileNotFoundError:
        previous = seed

    series = {item["series_id"]: item for item in previous["series"]}
    for item in seed["series"]:
        prior = series.get(item["series_id"])
        aliases = (prior.get("aliases") or []) + [prior["name"]] if prior is not None else []
        names = list(dict.fromkeys((item.get("aliases") or []) + aliases))
        series[item["series_id"]] = {**item, "aliases": [name for name in names if name != item["name"]]}
    for rule in config["matches"]:
        require(rule["series_id"] in series and string(rule["city"], 80)
                and optional_text(rule.get("province"), 80) and valid_distances(rule["distances"])
                and "year" in re.compile(rule["name_pattern"]).groupindex, "Invalid calendar match rule")
    candidates, complete = collect(config, today, now, fetch)
    events = merge_events(previous["events"], seed["events"], candidates)
    coverage = max(previous["coverage_through"], seed["coverage_through"])
    if complete:
        coverage = max(coverage, max(event["race_date"] for event in events
                                     if today <= iso_date(event["race_date"]) <= three_month_end(today)))
    return validate_snapshot({
        "schema_version": 1, "published_at": utc_timestamp(now), "coverage_through": coverage,
        "series": sorted(series.values(), key=lambda item: item["series_id"]), "events": events,
    })


def sync(config_path, output, today, now, dry_run, fetch=fetch_html):
    if dry_run:
        return build_snapshot(config_path, output, today, now, fetch)
    with write_lock(output):
        snapshot = build_snapshot(config_path, output, today, now, fetch)
        data = (json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        require(len(data) <= MAX_BYTES, "Candidate snapshot exceeds size limit")
        atomic_publish(output, data)
        return snapshot


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=CONFIG_PATH, help="trusted calendar source and series configuration")
    parser.add_argument("--output", type=Path, required=True, help="published calendar JSON path")
    parser.add_argument("--today", type=iso_date, help="override window start (default: current Shanghai date)")
    parser.add_argument("--dry-run", action="store_true", help="print validated snapshot without writing it")
    args = parser.parse_args()
    try:
        now = datetime.now(timezone.utc)
        result = sync(args.config, args.output, args.today or now.astimezone(SHANGHAI).date(), now, args.dry_run)
    except (OSError, ValueError, LookupError, TypeError) as error:
        print(f"Calendar sync failed: {error}", file=sys.stderr)
        return 1
    if args.dry_run:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print(f"Published {len(result['events'])} editions through {result['coverage_through']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Collect per-edition sub-three evidence and publish a read-only aggregate snapshot."""

import argparse
import csv
from datetime import datetime, timezone
from hashlib import sha256
from html.parser import HTMLParser
from io import StringIO
import json
from pathlib import Path
import re
import sys
import unicodedata
from urllib.error import HTTPError

from caa_marathon_directory import merge_directory, read_directory

from sync_marathon_events import (
    MAX_BYTES, SHANGHAI, SLUG, atomic_publish, fetch_html, https_url, iso_date, read_json, require,
    string, utc_timestamp, write_lock,
)

PILOT = Path(__file__).with_name("marathon_sub3_sources.json")
MAX_RESULT_BYTES = 16 * 1024 * 1024
MAX_EVENTS = 2000
SCOPE = "CN_MAINLAND_CAA_AB_MARATHON"
THRESHOLD = 3 * 60 * 60
OFFICIAL_EXACT_COUNT = "OFFICIAL_EXACT_COUNT"
EXACT_COUNT_BASIS = re.compile(r"(?:全马|全程马拉松)有效净(?:成绩|计时)(?:严格)?(?:小于|少于|<)(?:03:00:00|3小时)")
INVALID_RESULTS = {"DNF", "DNS", "DSQ", "DQ", "INVALID"}
NET_TIME = re.compile(r"([0-9]{1,2}):([0-5][0-9]):([0-5][0-9])")
EXCLUDED_PROVINCES = ("香港", "澳门", "澳門", "台湾", "臺灣", "台灣")
MARATHON_STATES = {"PLANNED", "CONFIRMED", "POSTPONED", "CANCELLED", "COMPLETED"}


def count_results(content, expected_rows):
    """Read a normalized official result export with one record per bib."""
    require(type(expected_rows) is int and 0 < expected_rows <= 150_000, "Invalid official result row count")
    reader = csv.DictReader(StringIO(content, newline=""), strict=True)
    require(reader.fieldnames == ["bib", "net_time", "status"], "Expected bib,net_time,status")
    bibs = set()
    verified_count = 0
    finishers = 0
    for row in reader:
        bib = row["bib"]
        require(None not in row and string(bib, 64) and bib == bib.strip() and bib not in bibs,
                "Invalid or repeated bib")
        bibs.add(bib)
        status = row["status"]
        if status in INVALID_RESULTS:
            continue
        require(status == "FINISHED", "Unknown official result status")
        match = NET_TIME.fullmatch(row["net_time"] or "")
        require(match is not None, "Invalid net finish time")
        hours, minutes, seconds = map(int, match.groups())
        duration = hours * 3600 + minutes * 60 + seconds
        require(duration > 0, "Net finish time must be positive")
        finishers += 1
        verified_count += duration < THRESHOLD
    require(len(bibs) == expected_rows, "Official result row count mismatch")
    return verified_count, finishers


class ArticleText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []
        self.ignored = 0

    def handle_starttag(self, tag, attrs):
        if tag in {"style", "script"}:
            self.ignored += 1
        elif tag in {"p", "div", "h1", "h2", "br"} and not self.ignored:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag in {"style", "script"}:
            self.ignored = max(0, self.ignored - 1)
        elif tag in {"p", "div", "h1", "h2"} and not self.ignored:
            self.parts.append("\n")

    def handle_data(self, data):
        if not self.ignored:
            self.parts.append(data)


def disclosure(html, config, strict=False):
    parsed = ArticleText()
    parsed.feed(html)
    parsed.close()
    article = unicodedata.normalize("NFKC", "".join(parsed.parts))
    article = re.sub(r"[^\S\n]+", "", article)
    title = unicodedata.normalize("NFKC", config["title"]).replace(" ", "")
    require(title in article, "Announcement does not match the registered edition")
    pattern = re.compile(config["pattern"])
    require("count" in pattern.groupindex, "Announcement pattern requires a named count")
    matches = list(pattern.finditer(article))
    require(len(matches) == 1, "Missing or ambiguous official disclosure")
    if strict:
        basis = matches[0].groupdict().get("basis")
        require(basis is not None and EXACT_COUNT_BASIS.fullmatch(basis), "Official report timing basis is ambiguous")
    count = matches[0].group("count")
    require(re.fullmatch(r"[0-9]{1,6}|[0-9]{1,3},[0-9]{3}", count) is not None, "Invalid disclosed count")
    number = int(count.replace(",", ""))
    require(number <= 150_000, "Official disclosure exceeds supported count range")
    return number


def release_evidence(config, root, now):
    require(config["timing_basis"] == "NET_TIME" and config["coverage"] == "ALL_RESULT_ROWS",
            "The result file must cover all rows and use net timing")
    path = (root / config["file"]).resolve()
    require(path.is_relative_to(root.resolve()), "Result file must be within the manifest directory")
    with path.open("rb") as handle:
        document = handle.read(MAX_RESULT_BYTES + 1)
    require(len(document) <= MAX_RESULT_BYTES, "Official result export is too large")
    digest = sha256(document).hexdigest()
    require(digest == config["sha256"], "Result file hash does not match the reviewed export")
    count, finishers = count_results(document.decode("utf-8-sig"), config["expected_rows"])
    return {"status": "VERIFIED", "verified_count": count, "finishers": finishers,
            "source_url": config["url"], "evidence_sha256": digest, "collected_at": utc_timestamp(now)}


def retained(previous, event, config):
    if previous is None or event["race_date"] != previous["race_date"]:
        return None
    if previous["status"] == "VERIFIED" and config.get("sha256") != previous.get("evidence_sha256"):
        return None
    if previous.get("source_url") == config["url"] and previous["status"] in {"VERIFIED", "DISCLOSED"}:
        return {**previous, **event, "stale": True}
    return None


def collect_row(catalog, event, root, now, previous, fetch):
    results = catalog.get("results")
    reports = [(catalog.get("strict_announcement"), True), (catalog.get("announcement"), False)]
    if results:
        try:
            return {**event, **release_evidence(results, root, now)}
        except (OSError, ValueError, csv.Error) as exc:
            print(f"Warning: {event['event_id']} results {type(exc).__name__}", file=sys.stderr)
            prior = retained(previous, event, results)
            if prior:
                return prior
    for report, strict in reports:
        if not report:
            continue
        try:
            document = fetch(report["url"])
            digest = sha256(document.encode("utf-8")).hexdigest()
            if strict:
                require(digest == report.get("sha256"), "Official report differs from the reviewed revision")
            count = disclosure(document, report, strict)
            evidence = {"source_url": report["url"], "evidence_sha256": digest,
                        "collected_at": utc_timestamp(now)}
            if strict:
                return {**event, **evidence, "status": "VERIFIED", "verified_count": count,
                        "evidence_kind": OFFICIAL_EXACT_COUNT}
            return {**event, **evidence, "status": "DISCLOSED", "disclosed_count": count}
        except (OSError, ValueError) as exc:
            reason = f"HTTP {exc.code}" if isinstance(exc, HTTPError) else type(exc).__name__
            channel = "strict report" if strict else "announcement"
            print(f"Warning: {event['event_id']} {channel} {reason}", file=sys.stderr)
            prior = retained(previous, event, report) if isinstance(exc, OSError) else None
            expected_kind = "VERIFIED" if strict else "DISCLOSED"
            if prior and prior["status"] == expected_kind:
                return prior
    status = "UNAVAILABLE" if results or any(report for report, _ in reports) else "PENDING"
    return {**event, "status": status}


def collect(manifest, root, now, prior=None, fetch=fetch_html):
    year = manifest["year"]
    today = now.astimezone(SHANGHAI).date()
    require(type(year) is int and 2000 <= year <= min(today.year, 2199), "Invalid ranking year")
    catalogs = manifest["events"]
    require(isinstance(catalogs, list) and 0 < len(catalogs) <= MAX_EVENTS, "Invalid event catalog size")
    catalog_coverage = manifest.get("catalog_coverage", "PARTIAL")
    require(catalog_coverage in {"PARTIAL", "COMPLETE"}, "Invalid catalog coverage")
    catalog_source = manifest.get("catalog_source")
    if catalog_source is not None:
        https_url(catalog_source)
    require(catalog_coverage != "COMPLETE" or catalog_source is not None, "Complete catalog requires a source")
    previous = {}
    if prior and prior.get("year") == year and prior.get("schema_version") == 1 and prior.get("scope") == SCOPE:
        previous = {row["event_id"]: row for row in prior["events"]}
    ids = set()
    events = []
    for catalog in catalogs:
        require(string(catalog["province"], 80), "Invalid event province")
        if (catalog["country"] != "CN" or catalog["province"].startswith(EXCLUDED_PROVINCES)
                or catalog["cert_level"] not in {"A", "B"} or catalog["distance"] != "MARATHON"):
            continue
        event_id = catalog["event_id"]
        race_date = iso_date(catalog["race_date"]) if catalog.get("race_date") else None
        require(isinstance(event_id, str) and SLUG.fullmatch(event_id) and event_id.endswith(f"_{year}")
                and event_id not in ids and (race_date is None or race_date.year == year),
                "Invalid or repeated event edition")
        ids.add(event_id)
        require(string(catalog["name"], 160), "Invalid event name")
        https_url(catalog["edition_source"])
        require(catalog["state"] in MARATHON_STATES, "Invalid event state")
        for key in ("results", "strict_announcement", "announcement"):
            if catalog.get(key):
                https_url(catalog[key]["url"])
        event = public_event(catalog)
        if catalog["state"] != "COMPLETED":
            if catalog["state"] != "CANCELLED":
                events.append({**event, "status": "PENDING"})
            continue
        require(race_date is not None and race_date <= today, "Completed event requires a past or current date")
        events.append(collect_row(catalog, event, root, now, previous.get(event_id), fetch))
    summary = {"schema_version": 1, "scope": SCOPE, "year": year, "as_of": today.isoformat(),
               "published_at": utc_timestamp(now), "catalog_coverage": catalog_coverage,
               "events": sorted(events, key=lambda event: (event.get("race_date") or "", event["event_id"]))}
    if catalog_source is not None:
        summary["catalog_source"] = catalog_source
    return summary


def public_event(catalog):
    event = {key: catalog[key] for key in ("event_id", "name", "race_date", "province", "cert_level", "edition_source")}
    aliases = catalog.get("aliases", [])
    require(isinstance(aliases, list) and len(aliases) <= 20 and all(string(alias, 80) for alias in aliases),
            "Invalid race aliases")
    if aliases:
        event["aliases"] = aliases
    if catalog.get("date_hint"):
        require(string(catalog["date_hint"], 80), "Invalid calendar date hint")
        event["date_hint"] = catalog["date_hint"]
    return event


def read_archived_html(url, directory, by_url):
    path = directory / by_url[url]
    with path.open("rb") as file:
        raw = file.read(MAX_BYTES + 1)
    require(len(raw) <= MAX_BYTES, "Archived announcement exceeds size limit")
    return raw.decode("utf-8-sig")


def run(manifest_path, output, dry_run, now, catalog_pdf=None, announcement_dir=None):
    manifest = read_json(manifest_path)
    if catalog_pdf:
        manifest = merge_directory(manifest, read_directory(catalog_pdf, manifest))
    fetch = fetch_html
    if announcement_dir:
        by_url = {}
        for row in manifest["events"]:
            disclosure_report, strict = row.get("announcement"), row.get("strict_announcement")
            if not (disclosure_report or strict):
                continue
            event_id = row["event_id"]
            require(isinstance(event_id, str) and SLUG.fullmatch(event_id), "Invalid archive event identifier")
            distinct = disclosure_report and strict and disclosure_report["url"] != strict["url"]
            if disclosure_report:
                by_url[disclosure_report["url"]] = f"{event_id}{'.disclosure' if distinct else ''}.html"
            if strict:
                by_url[strict["url"]] = f"{event_id}{'.strict' if distinct else ''}.html"
        fetch = lambda url: read_archived_html(url, announcement_dir, by_url)

    def gather():
        try:
            prior = read_json(output)
        except FileNotFoundError:
            prior = None
        if (catalog_pdf is None and "directory" in manifest and prior and prior.get("year") == manifest["year"]
                and len(prior["events"]) > len(manifest["events"])):
            raise ValueError("Supply the catalog PDF to retain the previously imported event catalog")
        summary = collect(manifest, manifest_path.parent, now, prior=prior, fetch=fetch)
        data = (json.dumps(summary, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
        require(len(data) <= MAX_BYTES, "Candidate snapshot exceeds size limit")
        return data

    if dry_run:
        print(gather().decode("utf-8"), end="")
    else:
        with write_lock(output):
            atomic_publish(output, gather())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=PILOT, help="Annual catalog and approved sources")
    parser.add_argument("--output", type=Path, default=Path(".run-agent/marathon-sub3.json"))
    parser.add_argument("--catalog-pdf", type=Path, help="Import the approved official CAA directory PDF")
    parser.add_argument("--announcement-dir", type=Path, help="Read UTF-8 HTML from <event_id>.html in this directory")
    parser.add_argument("--dry-run", action="store_true", help="Print the candidate snapshot without updating files")
    args = parser.parse_args()
    try:
        run(args.manifest, args.output, args.dry_run, datetime.now(timezone.utc),
            args.catalog_pdf, args.announcement_dir)
    except (OSError, ValueError, KeyError, TypeError, ImportError) as exc:
        parser.exit(1, f"Failed to write sub-three snapshot: {exc}\n")


if __name__ == "__main__":
    main()

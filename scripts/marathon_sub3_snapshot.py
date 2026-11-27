"""Validate the race-level snapshot consumed by the site and the host scheduler."""

import re

from sync_marathon_events import SHANGHAI, SLUG, https_url, iso_date, require, stamp, string

SCOPE = "CN_MAINLAND_CAA_AB_MARATHON"
OFFICIAL_EXACT_COUNT = "OFFICIAL_EXACT_COUNT"
MAX_EVENTS = 2000
MAX_FINISHERS = 150_000
SHA256 = re.compile(r"[0-9a-f]{64}")


def count(value):
    return type(value) is int and 0 <= value <= MAX_FINISHERS


def validate_event(row, year, as_of, published_at, seen):
    require(isinstance(row, dict), "Invalid sub-three event")
    edition, status = row.get("event_id"), row.get("status")
    require(string(edition, 80) and SLUG.fullmatch(edition) and edition.endswith(f"_{year}")
            and edition not in seen and string(row.get("name"), 160) and string(row.get("province"), 80)
            and row.get("cert_level") in ("A", "B") and status in ("VERIFIED", "DISCLOSED", "PENDING", "UNAVAILABLE"),
            "Invalid or repeated sub-three event")
    seen.add(edition)
    https_url(row.get("edition_source"))
    if row.get("race_date") is None:
        require(status == "PENDING", "Sub-three event date is missing")
    else:
        race_date = iso_date(row["race_date"])
        require(race_date.year == year and (status == "PENDING" or race_date <= as_of), "Invalid sub-three event date")
    aliases, date_hint = row.get("aliases"), row.get("date_hint")
    require(aliases is None or isinstance(aliases, list) and len(aliases) <= 20
            and all(string(value, 80) for value in aliases), "Invalid sub-three event aliases")
    require(date_hint is None or string(date_hint, 80), "Invalid sub-three event date hint")
    stale = row.get("stale")
    require(stale is None or type(stale) is bool, "Invalid sub-three evidence freshness")
    verified, finishers, disclosed = (row.get(key) for key in ("verified_count", "finishers", "disclosed_count"))
    source, digest, collected, kind = (row.get(key) for key in
                                      ("source_url", "evidence_sha256", "collected_at", "evidence_kind"))
    if status in ("PENDING", "UNAVAILABLE"):
        require(all(value is None for value in (verified, finishers, disclosed, source, digest, collected, kind))
                and not stale, "Unverified sub-three event contains result data")
        return
    https_url(source)
    require(string(digest, 64) and SHA256.fullmatch(digest) and stamp(collected) <= published_at,
            "Invalid sub-three evidence checksum or timestamp")
    if status == "VERIFIED":
        complete_export = kind is None and count(finishers) and count(verified) and verified <= finishers
        official_count = kind == OFFICIAL_EXACT_COUNT and finishers is None
        require(count(verified) and disclosed is None and (complete_export or official_count),
                "Invalid verified sub-three count")
    else:
        require(count(disclosed) and verified is None and finishers is None and kind is None,
                "Invalid disclosed sub-three count")


def validate_snapshot(value):
    require(isinstance(value, dict) and type(value.get("schema_version")) is int and value["schema_version"] == 1
            and value.get("scope") == SCOPE and type(value.get("year")) is int and 2000 <= value["year"] <= 2199
            and value.get("catalog_coverage") in ("PARTIAL", "COMPLETE")
            and isinstance(value.get("events"), list) and len(value["events"]) <= MAX_EVENTS,
            "Invalid sub-three snapshot header")
    as_of = iso_date(value.get("as_of"))
    published_at = stamp(value.get("published_at"))
    require(as_of.year >= value["year"] and published_at.astimezone(SHANGHAI).date() == as_of,
            "Invalid sub-three snapshot timestamps")
    if value.get("catalog_source") is not None or value["catalog_coverage"] == "COMPLETE":
        https_url(value.get("catalog_source"))
    seen = set()
    for event in value["events"]:
        validate_event(event, value["year"], as_of, published_at, seen)
    return value

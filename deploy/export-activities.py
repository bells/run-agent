"""Create the minimal read-only snapshot consumed by Running MCP Server."""

import hmac
import json
import math
import os
import re
import secrets
import stat
import sys
import tempfile
from datetime import datetime
from pathlib import Path
from typing import Optional

MAX_SOURCE_BYTES = 64 * 1024 * 1024
MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024
MAX_DURATION_SECONDS = (1 << 63) - 1
LOCAL_DATE = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}(?:[ T].+)?")
DURATION = re.compile(r"(?:(\d+) days?, )?(\d+):([0-5]\d):([0-5]\d)", re.ASCII)


def read_key(path: Path) -> bytes:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if not path.exists():
        with tempfile.NamedTemporaryFile(dir=path.parent) as candidate:
            candidate.write(secrets.token_bytes(32))
            candidate.flush()
            try:
                os.link(candidate.name, path)
            except FileExistsError:
                pass

    metadata = path.lstat()
    if (
        not stat.S_ISREG(metadata.st_mode)
        or metadata.st_uid != os.getuid()
        or stat.S_IMODE(metadata.st_mode) & 0o077
    ):
        raise ValueError("Export key must be an owner-only file")
    with path.open("rb") as source:
        key = source.read(33)
    if len(key) != 32:
        raise ValueError("Export key is invalid")
    return key


def normalize_date(value: str) -> str:
    if not isinstance(value, str) or not LOCAL_DATE.fullmatch(value):
        raise ValueError("Invalid local activity date")
    date = datetime.fromisoformat(value).date()
    # Retain the legacy parser's timestamp shape while omitting the actual time.
    return f"{date.isoformat()} 00:00:00"


def duration_seconds(value: str) -> int:
    match = DURATION.fullmatch(value) if isinstance(value, str) else None
    if match is None:
        raise ValueError("Invalid moving time")
    days, hours, minutes, seconds = (int(part or 0) for part in match.groups())
    return days * 86_400 + hours * 3_600 + minutes * 60 + seconds


def export(source: Path, key_path: Optional[Path]) -> bytes:
    with source.open("rb") as file:
        data = file.read(MAX_SOURCE_BYTES + 1)
    if len(data) > MAX_SOURCE_BYTES:
        raise ValueError("Activity source exceeds 64 MiB")
    rows = json.loads(data.decode("utf-8"))
    if not isinstance(rows, list):
        raise ValueError("Expected a JSON array")

    activities = []
    source_ids = set()
    total_seconds = 0
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("type"), str):
            raise ValueError("Invalid activity row")
        if row["type"] != "Run":
            continue
        source_id = row.get("run_id")
        distance = row.get("distance")
        if (
            type(source_id) is not int
            or source_id in source_ids
            or type(distance) not in (int, float)
            or not math.isfinite(distance)
            or distance < 0
        ):
            raise ValueError("Invalid running activity ID or distance")
        source_ids.add(source_id)
        seconds = duration_seconds(row.get("moving_time"))
        total_seconds += seconds
        if total_seconds > MAX_DURATION_SECONDS:
            raise ValueError("Moving time exceeds the supported range")
        hours, remaining = divmod(seconds, 3_600)
        minutes, seconds = divmod(remaining, 60)
        activities.append({
            "run_id": source_id,
            "type": "Run",
            "distance": distance,
            "moving_time": f"{hours}:{minutes:02d}:{seconds:02d}",
            "start_date_local": normalize_date(row.get("start_date_local")),
        })

    if activities and key_path is not None:
        key = read_key(key_path)
        opaque_ids = set()
        for row in activities:
            digest = hmac.digest(key, str(row["run_id"]).encode("ascii"), "sha256")
            opaque_id = int.from_bytes(digest[:8], "big") >> 11
            if opaque_id in opaque_ids:
                raise ValueError("Internal activity ID collision")
            opaque_ids.add(opaque_id)
            row["run_id"] = opaque_id

    snapshot = json.dumps(activities, allow_nan=False, separators=(",", ":")).encode("utf-8") + b"\n"
    if len(snapshot) > MAX_SNAPSHOT_BYTES:
        raise ValueError("Activity snapshot exceeds 8 MiB")
    return snapshot


if __name__ == "__main__":
    public = len(sys.argv) == 3 and sys.argv[1] == "--public"
    if not public and len(sys.argv) != 2:
        sys.exit("Usage: export-activities.py [--public] <activities.json>")
    try:
        key_path = None
        if not public:
            default_key_path = Path.home() / ".local/state/run-agent/export-key"
            key_path = Path(os.environ.get("RUN_AGENT_EXPORT_KEY_FILE") or default_key_path)
        snapshot = export(Path(sys.argv[-1]), key_path)
    except (OSError, UnicodeError, ValueError, OverflowError) as error:
        sys.exit(f"Could not export activities: {error.__class__.__name__}")
    sys.stdout.buffer.write(snapshot)

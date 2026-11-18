"""Project a Watson Running export onto the RunAgent read-only data contract."""

import json
import math
import sys
from pathlib import Path


def export(source: Path) -> list[dict]:
    rows = json.loads(source.read_text(encoding="utf-8"))
    if not isinstance(rows, list):
        raise ValueError("Expected a JSON array")

    fields = ("run_id", "type", "distance", "moving_time", "start_date_local")
    activities = []
    for index, row in enumerate(rows, start=1):
        if not isinstance(row, dict):
            raise ValueError(f"Row {index} must be an object")
        if row.get("type") != "Run":
            continue
        if not all(field in row for field in fields):
            raise ValueError(f"Running row {index} is missing a required field")
        distance = row["distance"]
        if (
            type(row["run_id"]) is not int
            or type(distance) not in (int, float)
            or not math.isfinite(distance)
            or distance < 0
            or not isinstance(row["moving_time"], str)
            or not isinstance(row["start_date_local"], str)
        ):
            raise ValueError(f"Running row {index} contains invalid field types")
        activities.append({field: row[field] for field in fields})
    return activities


if __name__ == "__main__":
    try:
        activities = export(Path(sys.argv[1]))
    except (OSError, UnicodeError, ValueError, OverflowError) as error:
        sys.exit(f"Could not export activities: {error.__class__.__name__}")
    json.dump(activities, sys.stdout, ensure_ascii=False, separators=(",", ":"))
    print()

"""Parse private running files locally and export only aggregate metrics."""

from dataclasses import dataclass
from io import BytesIO
import json
import math

MAX_BYTES = 32 * 1024 * 1024
MAX_INTERVALS = 150_000


@dataclass(frozen=True)
class Interval:
    duration_s: float
    distance_m: float
    heart_rate: float | None

    def __post_init__(self):
        for value, minimum, maximum in ((self.duration_s, 0, 86400), (self.distance_m, -1, 250000)):
            if type(value) not in (int, float) or not math.isfinite(value) or not minimum < value <= maximum:
                raise ValueError("Invalid training duration or distance")
        if self.distance_m < 0:
            raise ValueError("Training distance cannot be negative")
        rate = self.heart_rate
        if rate is not None and (type(rate) not in (int, float) or not math.isfinite(rate) or not 0 < rate <= 300):
            raise ValueError("Invalid heart rate")


def mean_rate(intervals):
    if not intervals or any(row.heart_rate is None for row in intervals):
        return None
    return sum(row.duration_s * row.heart_rate for row in intervals) / sum(row.duration_s for row in intervals)


def efficiency(intervals):
    heart_rate = mean_rate(intervals)
    if heart_rate is None:
        return None
    speed = 60 * sum(row.distance_m for row in intervals) / sum(row.duration_s for row in intervals)
    return speed / heart_rate


def split_time(intervals, boundary):
    first, second = [], []
    cursor = 0
    for row in intervals:
        part = min(row.duration_s, max(0, boundary - cursor))
        if part:
            first.append(Interval(part, row.distance_m * part / row.duration_s, row.heart_rate))
        rest = row.duration_s - part
        if rest:
            second.append(Interval(rest, row.distance_m * rest / row.duration_s, row.heart_rate))
        cursor += row.duration_s
    return first, second


def kilometer_splits(intervals):
    kilometers, batch = [], []
    distance = 0
    for row in intervals:
        if row.distance_m == 0:
            batch.append(row)
            continue
        remaining = row.distance_m
        while distance + remaining >= 1000:
            part = 1000 - distance
            batch.append(Interval(row.duration_s * part / row.distance_m, part, row.heart_rate))
            rate = mean_rate(batch)
            kilometers.append({"km": len(kilometers) + 1,
                               "pace_s": round(sum(chunk.duration_s for chunk in batch), 1),
                               "heart_rate": round(rate, 1) if rate is not None else None})
            remaining -= part
            distance = 0
            batch = []
        if remaining > 0:
            batch.append(Interval(row.duration_s * remaining / row.distance_m, remaining, row.heart_rate))
            distance += remaining
    return kilometers


def summarize(intervals):
    if not 0 < len(intervals) <= MAX_INTERVALS:
        raise ValueError("Unexpected number of training intervals")
    time = sum(row.duration_s for row in intervals)
    distance = sum(row.distance_m for row in intervals)
    if distance <= 0 or distance > 500_000 or time > 2 * 86400:
        raise ValueError("Training duration or distance outside supported range")
    first, second = split_time(intervals, time / 2)
    first_ef, second_ef = efficiency(first), efficiency(second)
    rate = mean_rate(intervals)
    overall = efficiency(intervals)
    drift = None if not first_ef or second_ef is None else (first_ef - second_ef) / first_ef * 100
    kilometers = kilometer_splits(intervals)
    fade = None
    if len(kilometers) >= 2 and kilometers[0]["pace_s"] > 0:
        start, end = kilometers[0]["pace_s"], kilometers[-1]["pace_s"]
        fade = round((end - start) / start * 100, 2)
    return {"duration_s": round(time, 2), "distance_m": round(distance, 2),
            "average_pace_s": round(time * 1000 / distance, 2), "mean_hr": round(rate, 2) if rate else None,
            "efficiency": round(overall, 3) if overall is not None else None,
            "drift_pct": round(drift, 2) if drift is not None else None,
            "pace_fade_pct": fade, "kilometers": kilometers}


def gpx_intervals(raw):
    import gpxpy

    if b"<!DOCTYPE" in raw.upper() or b"<!ENTITY" in raw.upper():
        raise ValueError("GPX cannot contain DTD or entity declarations")
    track = gpxpy.parse(raw.decode("utf-8-sig"))
    intervals = []
    for route in track.tracks:
        for segment in route.segments:
            for previous, current in zip(segment.points, segment.points[1:]):
                if previous.time is None or current.time is None:
                    raise ValueError("GPX track points need timestamps")
                distance = previous.distance_2d(current)
                hr = next((e.text for ext in current.extensions for e in ext.iter()
                           if str(e.tag).split("}")[-1] == "hr" and e.text), None)
                intervals.append(Interval((current.time - previous.time).total_seconds(), distance,
                                          float(hr) if hr is not None else None))
                if len(intervals) > MAX_INTERVALS:
                    raise ValueError("GPX contains too many intervals")
    return intervals


def fit_intervals(raw):
    from fitparse import FitFile

    intervals = []
    previous = None
    for record in FitFile(BytesIO(raw)).get_messages("record"):
        data = record.get_values()
        if data.get("timestamp") is None or data.get("distance") is None:
            continue
        if previous is not None:
            intervals.append(Interval((data["timestamp"] - previous["timestamp"]).total_seconds(),
                                      data["distance"] - previous["distance"], data.get("heart_rate")))
            if len(intervals) > MAX_INTERVALS:
                raise ValueError("FIT contains too many intervals")
        previous = data
    return intervals


def read_training(path):
    with path.open("rb") as source:
        raw = source.read(MAX_BYTES + 1)
    if len(raw) > MAX_BYTES:
        raise ValueError("Training file is too large")
    extension = path.suffix.lower()
    if extension == ".json":
        rows = json.loads(raw)["intervals"]
        if not isinstance(rows, list) or len(rows) > MAX_INTERVALS:
            raise ValueError("Unexpected list of training intervals")
        return [Interval(row["duration_s"], row["distance_m"], row.get("heart_rate")) for row in rows]
    if extension == ".gpx":
        return gpx_intervals(raw)
    if extension == ".fit":
        return fit_intervals(raw)
    raise ValueError("Expected a FIT, GPX or interval JSON file")

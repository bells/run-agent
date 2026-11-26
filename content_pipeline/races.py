"""Build factual article inputs from the existing RunAgent race snapshots."""

from datetime import date, timedelta
import re
from zoneinfo import ZoneInfo

DISTANCE_METERS = {"MARATHON": 42195, "HALF_MARATHON": 21097.5}
FINISH_TIME = re.compile(r"[0-9]{2}:[0-5][0-9]:[0-5][0-9]")


def race_facts(calendar, podiums, *, event_id=None, athlete=None):
    if bool(event_id) == bool(athlete):
        raise ValueError("Choose an event edition or athlete")
    series = {item["series_id"]: item["name"] for item in calendar["series"]}
    selected = []
    keys = set()
    for podium in podiums:
        if event_id and event_id != podium["event_id"]:
            continue
        race_date = date.fromisoformat(podium["race_date"]).isoformat()
        distance = DISTANCE_METERS[podium["distance"]]
        for group in podium["groups"]:
            for row in group["results"]:
                if athlete and athlete.casefold().strip() != row["athlete_name"].casefold().strip():
                    continue
                raw_time = row["finish_time"]
                if not FINISH_TIME.fullmatch(raw_time):
                    raise ValueError("Invalid official finish time")
                hours, minutes, seconds = map(int, raw_time.split(":"))
                total = hours * 3600 + minutes * 60 + seconds
                rank = row["rank"]
                if total <= 0 or type(rank) is not int or not 1 <= rank <= 3:
                    raise ValueError("Invalid podium result")
                key = (podium["event_id"], podium["distance"], group["category"], group["sub_category"], rank)
                if key in keys:
                    raise ValueError("Repeated official podium result")
                keys.add(key)
                selected.append({
                    "race_date": race_date, "name": series[podium["series_id"]], "distance_m": distance,
                    "category": group["category"], "sub_category": group["sub_category"],
                    "athlete": row["athlete_name"], "rank": rank, "finish_time": raw_time,
                    "pace_s": round(total * 1000 / distance), "source": group["source"],
                })
    if not selected:
        raise ValueError("No substantiated podium results matched the selection")
    selected.sort(key=lambda row: row["race_date"], reverse=True)
    return {"subject": athlete or selected[0]["name"], "results": selected[:20]}


def upcoming_facts(calendar, now, days, region=None, level=None):
    if not 14 <= days <= 28:
        raise ValueError("Preview window must be between 14 and 28 days")
    series = {item["series_id"]: item["name"] for item in calendar["series"]}
    selected = []
    for row in calendar["events"]:
        today = now.astimezone(ZoneInfo(row["timezone"])).date()
        race_date = date.fromisoformat(row["race_date"])
        if (row["status"] not in {"CONFIRMED", "PLANNED"} or not today <= race_date < today + timedelta(days=days)
                or (region and region not in (row["country"], row["city"], row.get("province")))
                or (level and level != row.get("cert_level"))):
            continue
        registration = row.get("reg_deadline")
        registration_days = (date.fromisoformat(registration) - today).days if registration else None
        selected.append({"name": series[row["series_id"]], "race_date": str(race_date), "city": row["city"],
                         "distances": row["distances"], "registration_days": registration_days,
                         "registration_deadline": registration, "cert_level": row.get("cert_level"),
                         "status": row["status"], "source": row["source"]})
    return {"window_days": days, "as_of": now.astimezone(ZoneInfo("Asia/Shanghai")).date().isoformat(),
            "events": sorted(selected, key=lambda row: (row["race_date"], row["name"]))}


def nutrition_facts(notes):
    claims = notes["claims"]
    if not isinstance(claims, list) or not 1 <= len(claims) <= 15:
        raise ValueError("Nutrition preview needs between one and 15 reviewed claims")
    return {"subject": notes["subject"], "season": notes["season"], "phase": notes["phase"],
            "claims": [{"text": item["text"], "source": item["source"]} for item in claims]}

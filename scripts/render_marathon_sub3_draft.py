#!/usr/bin/env python3
"""Export a marathon sub-three fact sheet and WeChat-oriented HTML preview."""

import argparse
from hashlib import sha256
from html import escape
import json
from pathlib import Path
import shutil
import tempfile

from collect_marathon_sub3 import MAX_EVENTS, OFFICIAL_EXACT_COUNT, SCOPE
from sync_marathon_events import https_url, iso_date, read_json, require, stamp, string, write_lock


def fact_sheet(snapshot):
    require(snapshot["schema_version"] == 1 and snapshot["scope"] == SCOPE, "Unknown marathon snapshot format")
    year = snapshot["year"]
    as_of = iso_date(snapshot["as_of"])
    require(type(year) is int and 2000 <= year <= as_of.year, "Invalid ranking year")
    stamp(snapshot["published_at"])
    coverage = snapshot["catalog_coverage"]
    require(coverage in {"PARTIAL", "COMPLETE"}, "Invalid catalog coverage")
    events = snapshot["events"]
    require(isinstance(events, list) and len(events) <= MAX_EVENTS, "Invalid event list")
    verified = []
    disclosed = []
    ids = set()
    for row in events:
        event_id = row["event_id"]
        require(string(event_id, 80) and event_id not in ids and event_id.endswith(f"_{year}")
                and string(row["name"], 160), "Invalid or duplicate event")
        ids.add(event_id)
        timing_status = row["status"]
        require(timing_status in {"VERIFIED", "DISCLOSED", "PENDING", "UNAVAILABLE"}, "Invalid evidence status")
        if timing_status in {"PENDING", "UNAVAILABLE"}:
            continue
        date = iso_date(row["race_date"])
        require(date.year == year and date <= as_of, "Invalid event date")
        count = row["verified_count"] if timing_status == "VERIFIED" else row["disclosed_count"]
        require(type(count) is int and 0 <= count <= 150_000, "Invalid sub-three count")
        https_url(row["source_url"])
        stamp(row["collected_at"])
        if timing_status == "VERIFIED":
            finishers = row.get("finishers")
            require((row.get("evidence_kind") == OFFICIAL_EXACT_COUNT and finishers is None)
                    or (type(finishers) is int and count <= finishers <= 150_000), "Invalid total finishers")
        evidence = {"event_id": event_id, "name": row["name"], "race_date": str(date), "count": count,
                    "source_url": row["source_url"], "stale": row.get("stale") is True,
                    "collected_at": row["collected_at"]}
        (verified if timing_status == "VERIFIED" else disclosed).append(evidence)
    verified.sort(key=lambda row: (-row["count"], row["event_id"]))
    rankings = []
    rank = 0
    for position, row in enumerate(verified[:10]):
        if position == 0 or row["count"] != verified[position - 1]["count"]:
            rank = position + 1
        rankings.append({"rank": rank, **row})
    return {"year": year, "scope": SCOPE, "as_of": str(as_of), "published_at": snapshot["published_at"],
            "threshold": "NET_TIME < 03:00:00", "catalog_coverage": coverage, "catalogued_count": len(events),
            "verified_count": len(verified), "rankings": rankings, "disclosed": disclosed,
            "cutoff_tie": len(verified) > 10 and verified[9]["count"] == verified[10]["count"]}


def table(rows, ranked):
    if not rows:
        return ""
    table_style = "width:100%;border-collapse:collapse;font-size:15px;line-height:1.5;"
    cell_style = "border-bottom:1px solid #dce4ec;text-align:left;padding:9px 4px;"
    columns = ["名次", "赛事", "人数", "出处"] if ranked else ["赛事", "官方披露", "出处"]
    headings = "".join(f'<th style="{cell_style}">{name}</th>' for name in columns)
    data = []
    for row in rows:
        source = escape(row["source_url"], quote=True)
        name = escape(row["name"])
        count = f'{row["count"]}{"（上次采集）" if row["stale"] else ""}'
        values = [name, count, f'<a style="color:#075985;" href="{source}">原文</a>']
        if ranked:
            values.insert(0, str(row["rank"]))
        data.append("<tr>" + "".join(f'<td style="{cell_style}">{value}</td>' for value in values) + "</tr>")
    return f'<table style="{table_style}"><thead><tr>{headings}</tr></thead><tbody>{"".join(data)}</tbody></table>'


def render_html(facts):
    title = f'{facts["year"]} 年马拉松破三统计'
    ranking = table(facts["rankings"], True)
    if ranking:
        ranking = ('<img alt="已核验赛事破三人数对比，赛事名称见下表" src="ranking.png" '
                   'style="display:block;width:100%;height:auto;margin:18px 0;" />' + ranking)
    else:
        ranking = "<p>目前没有满足口径的已核验赛事，无法排名。</p>"
    disclosures = table(facts["disclosed"], False)
    if disclosures:
        disclosures = "<h2>官方披露 · 口径待核实</h2>" + disclosures
    caveat = "<p>年度候选目录仍在补录。</p>" if facts["catalog_coverage"] == "PARTIAL" else ""
    tie = "<p>榜尾还有同人数赛事。</p>" if facts["cutoff_tie"] else ""
    return f"""<!doctype html>
<html lang="zh-CN">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title></head>
<body style="margin:0;background:#ffffff;">
<section style="margin:0 auto;padding:24px 14px;max-width:640px;color:#13263a;
font-family:system-ui,sans-serif;line-height:1.65;">
<h1 style="margin:0 0 12px;font-size:26px;line-height:1.3;">{title}</h1>
<p style="background:#eff6ff;border-left:4px solid #0369a1;padding:12px;margin:0;">
中国大陆田协 A/B 类全马，有效净成绩严格小于 3 小时。
统计截至 {facts["as_of"]}，已核验 {facts["verified_count"]} 场，快照收录 {facts["catalogued_count"]} 场候选赛事。</p>
{caveat}
<h2>已核验赛事</h2>
{ranking}{tie}{disclosures}
</section>
</body></html>
"""


def chart(rows, path):
    from matplotlib.backends.backend_agg import FigureCanvasAgg
    from matplotlib.figure import Figure

    figure = Figure(figsize=(6, max(1.7, len(rows) * 0.53 + 0.5)), dpi=180, facecolor="white")
    FigureCanvasAgg(figure)
    axes = figure.subplots()
    counts = [row["count"] for row in rows]
    y = list(range(len(rows)))
    axes.barh(y, counts, height=0.55, color="#0369a1")
    axes.set_yticks(y, [f'#{row["rank"]}' for row in rows], fontsize=15, color="#13263a")
    axes.invert_yaxis()
    axes.set_xlim(0, max(1, max(counts) * 1.22))
    axes.spines[:].set_visible(False)
    axes.tick_params(axis="both", length=0)
    axes.xaxis.set_visible(False)
    for position, count in zip(y, counts):
        axes.annotate(f"{count:,}", (count, position), xytext=(6, 0), textcoords="offset points",
                      va="center", fontsize=16, color="#13263a")
    figure.tight_layout()
    figure.savefig(path)


def write_draft(snapshot, output):
    facts = fact_sheet(snapshot)
    encoded = (json.dumps(facts, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    version = sha256(encoded).hexdigest()[:12]
    destination = output / f'marathon-sub3-{facts["year"]}-{version}'
    with write_lock(destination):
        if destination.is_dir():
            return destination
        staging = Path(tempfile.mkdtemp(prefix=f".{destination.name}.", dir=output))
        try:
            if facts["rankings"]:
                chart(facts["rankings"], staging / "ranking.png")
            (staging / "facts.json").write_bytes(encoded)
            (staging / "preview.html").write_text(render_html(facts), encoding="utf-8")
            staging.rename(destination)
        finally:
            if staging.exists():
                shutil.rmtree(staging)
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, default=Path(".run-agent/marathon-sub3.json"))
    parser.add_argument("--output", type=Path, default=Path("output/drafts"))
    parser.add_argument("--dry-run", action="store_true", required=True, help="Export locally without credentials")
    args = parser.parse_args()
    try:
        print(write_draft(read_json(args.snapshot), args.output))
    except (OSError, ValueError, KeyError, TypeError, ImportError) as exc:
        parser.exit(1, f"Failed to export draft: {exc}\n")


if __name__ == "__main__":
    main()

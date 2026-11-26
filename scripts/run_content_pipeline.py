#!/usr/bin/env python3
"""Generate local running content or explicitly add a WeChat draft."""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from content_pipeline.preview import WRITERS, export_preview
from content_pipeline.races import nutrition_facts, race_facts, upcoming_facts
from content_pipeline.training import read_training, summarize
from content_pipeline.wechat_client import WeChatClient, WeChatError
from render_marathon_sub3_draft import write_draft
from sync_marathon_events import read_json, validate_snapshot

MARATHON = ROOT / "src/main/resources/marathon"


def choices(args):
    if args.type == "race_news_athlete":
        return race_facts(read_json(args.calendar), read_json(args.podiums),
                          event_id=args.event_id, athlete=args.athlete)
    if args.type == "event_preview":
        return upcoming_facts(validate_snapshot(read_json(args.calendar)), datetime.now(timezone.utc),
                              args.days, args.region, args.level)
    if args.type == "nutrition_guide":
        if not args.input:
            raise ValueError("Supply --input with reviewed nutrition claims and sources")
        return nutrition_facts(read_json(args.input))
    if not args.input:
        raise ValueError("Supply --input with a local FIT, GPX or interval JSON file")
    return {**summarize(read_training(args.input)), "synthetic": args.synthetic}


def generate(args):
    if args.type != "marathon_sub3":
        destination = export_preview(args.type, choices(args), args.output)
        title = read_json(destination / "facts.json")["title"]
        body = (destination / "body.html").read_text(encoding="utf-8")
        return destination, title, body, destination / "training.png"
    snapshot = read_json(args.snapshot)
    destination = write_draft(snapshot, args.output)
    preview = (destination / "preview.html").read_text(encoding="utf-8")
    body = preview.split('<body style="margin:0;background:#ffffff;">', 1)[1].split("</body>", 1)[0]
    return destination, f'{snapshot["year"]} 年马拉松破三统计', body, destination / "ranking.png"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--type", choices=[*WRITERS, "marathon_sub3"], required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--dry-run", action="store_true", help="Generate locally, with no network or WeChat credentials")
    mode.add_argument("--push-draft", action="store_true", help="Add a new draft to WeChat for manual review")
    parser.add_argument("--input", type=Path)
    parser.add_argument("--event-id")
    parser.add_argument("--athlete")
    parser.add_argument("--calendar", type=Path, default=MARATHON / "marathon_events.json")
    parser.add_argument("--podiums", type=Path, default=MARATHON / "marathon_podiums.json")
    parser.add_argument("--snapshot", type=Path, default=Path(".run-agent/marathon-sub3.json"))
    parser.add_argument("--output", type=Path, default=Path("output/drafts"))
    parser.add_argument("--days", type=int, default=28)
    parser.add_argument("--region")
    parser.add_argument("--level")
    parser.add_argument("--synthetic", action="store_true", help="Label synthetic training data in the preview")
    parser.add_argument("--cover", type=Path, help="Optional local PNG/JPEG cover you have rights to use")
    args = parser.parse_args()
    try:
        if args.synthetic and (args.push_draft or args.type != "data_tutorial"):
            raise ValueError("--synthetic is only allowed for a training dry run")
        directory, title, body, generated_cover = generate(args)
        print(directory)
        if args.push_draft:
            cover = args.cover or generated_cover
            if not cover.is_file():
                raise ValueError("Provide a locally licensed image via --cover for this draft")
            client = WeChatClient(os.getenv("WECHAT_APP_ID"), os.getenv("WECHAT_APP_SECRET"),
                                  Path(".run-agent/wechat"))
            result = client.add_draft(title, body, directory, cover)
            print(json.dumps({"media_id": result, "review": "请在微信公众号后台草稿箱打开并审阅。"},
                             ensure_ascii=False))
    except (OSError, KeyError, TypeError, ValueError, ImportError, WeChatError) as error:
        parser.exit(1, f"Could not prepare the draft: {error}\n")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Find recent official or approved marathon source links with Tavily."""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
from urllib.request import Request, build_opener

from sync_marathon_events import NoRedirects

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from content_pipeline.discovery import discover


def search(api_key, body):
    request = Request("https://api.tavily.com/search", method="POST", data=json.dumps(body).encode(),
                      headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"})
    try:
        with build_opener(NoRedirects()).open(request, timeout=15) as reply:
            data = reply.read(1024 * 1024 + 1)
        if len(data) > 1024 * 1024:
            raise ValueError("Response is too large")
        return json.loads(data)
    except (OSError, ValueError):
        raise RuntimeError("Tavily source discovery failed") from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("topic")
    parser.add_argument("--domain", required=True, action="append")
    parser.add_argument("--hours", type=int, default=72)
    args = parser.parse_args()
    key = os.getenv("RUN_AGENT_MARATHON_TAVILY_API_KEY")
    if not key:
        parser.error("Set RUN_AGENT_MARATHON_TAVILY_API_KEY")
    try:
        result = discover(args.topic, args.domain, args.hours, datetime.now(timezone.utc),
                          lambda body: search(key, body))
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (RuntimeError, KeyError, TypeError, ValueError) as exc:
        parser.exit(1, f"{exc}\n")


if __name__ == "__main__":
    main()

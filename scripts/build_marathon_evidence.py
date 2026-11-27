#!/usr/bin/env python3
"""Publish markdown and a searchable snapshot from reviewed public race sources."""

import argparse
from hashlib import sha256
import json
from pathlib import Path
import unicodedata

from sync_marathon_events import SLUG, atomic_publish, https_url, iso_date, read_json, require, string, write_lock

SOURCE = Path(__file__).with_name("marathon_evidence_sources.json")
KINDS = {"COURSE_GUIDE", "INTERVIEW", "RACE_REPORT"}
FIELDS = {"id", "raceId", "year", "kind", "title", "publisher", "url", "publishedAt", "aliases", "paragraphs"}


def safe_text(value, limit):
    return string(value, limit) and all(unicodedata.category(char) != "Cf" for char in value)


def checked_documents(corpus):
    require(set(corpus) == {"schemaVersion", "documents"} and type(corpus["schemaVersion"]) is int
            and corpus["schemaVersion"] == 1, "Unsupported marathon evidence format")
    documents = corpus["documents"]
    require(isinstance(documents, list) and 1 <= len(documents) <= 300, "Invalid marathon evidence document count")
    used = set()
    for document in documents:
        require(isinstance(document, dict) and FIELDS <= set(document) <= FIELDS | {"distance"},
                "Invalid marathon evidence record")
        require(document.get("distance") in (None, "MARATHON", "HALF_MARATHON"), "Invalid evidence project")
        key = document["id"]
        require(string(key, 80) and SLUG.fullmatch(key) and key not in used
                and string(document["raceId"], 80) and SLUG.fullmatch(document["raceId"]),
                "Invalid or duplicate marathon evidence identifier")
        used.add(key)
        require(type(document["year"]) is int and 1900 <= document["year"] <= 2199
                and document["kind"] in KINDS and safe_text(document["title"], 160)
                and safe_text(document["publisher"], 80), "Invalid marathon evidence metadata")
        https_url(document["url"])
        require(iso_date(document["publishedAt"]).year >= 1900, "Invalid marathon evidence publication date")
        for field, size in (("aliases", 80), ("paragraphs", 480)):
            values = document[field]
            require(isinstance(values, list) and 1 <= len(values) <= 12
                    and all(safe_text(value, size) for value in values), "Invalid marathon evidence text")
        require(all(len(alias.strip()) >= 2 for alias in document["aliases"]), "Invalid marathon evidence aliases")
    return sorted(documents, key=lambda document: document["id"])


def publish_corpus(corpus, output, markdown_root):
    documents = checked_documents(corpus)
    payload = (json.dumps({"schemaVersion": 1, "documents": documents},
                          ensure_ascii=False, indent=2) + "\n").encode()
    require(len(payload) <= 1024 * 1024, "Marathon evidence snapshot exceeds size limit")
    version = markdown_root / sha256(payload).hexdigest()[:12]
    with write_lock(output):
        version.mkdir(parents=True, exist_ok=True)
        for document in documents:
            body = "\n\n".join(document["paragraphs"])
            artifact = (f'# {document["title"]}\n\n'
                        f'{document["publisher"]} · {document["publishedAt"]}\n\n'
                        f'{body}\n\n[原文]({document["url"]})\n')
            atomic_publish(version / f'{document["id"]}.md', artifact.encode())
        atomic_publish(output, payload)
    return version


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=SOURCE)
    parser.add_argument("--output", type=Path, default=Path(".run-agent/marathon-evidence.json"))
    parser.add_argument("--markdown-dir", type=Path, default=Path(".run-agent/marathon-evidence"))
    args = parser.parse_args()
    try:
        print(publish_corpus(read_json(args.source), args.output, args.markdown_dir))
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f"Marathon evidence export failed: {error}\n")


if __name__ == "__main__":
    main()

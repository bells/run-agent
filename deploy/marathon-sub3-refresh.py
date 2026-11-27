#!/usr/bin/env python3
"""Collect and publish the host's RunAgent marathon sub-three snapshot."""

import argparse
import json
import os
from pathlib import Path
import stat
import subprocess
import sys


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))

from marathon_sub3_snapshot import validate_snapshot as validate_sub3_snapshot
from sync_marathon_events import MAX_BYTES, atomic_publish, reject_duplicate_properties, require, write_lock


COLLECTOR_TIMEOUT_SECONDS = 8 * 60
SNAPSHOT_NAME = "marathon-sub3.json"
PREVIOUS_NAME = ".marathon-sub3.previous.json"


def output_directory():
    release = Path(os.environ.get("RUN_AGENT_DEPLOY_DIR") or Path.home() / ".local/share/run-agent")
    require(release.is_absolute(), "RUN_AGENT_DEPLOY_DIR must be an absolute path")
    directory = release.resolve(strict=True) / "marathon-data"
    info = directory.lstat()
    require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid(),
            "marathon-data must be a directory owned by the deployment user")
    return directory


def require_regular_path(path):
    try:
        mode = path.lstat().st_mode
    except FileNotFoundError:
        return
    require(stat.S_ISREG(mode), f"{path.name} must be a regular file")


def validate_snapshot(data):
    require(0 < len(data) <= MAX_BYTES, "Sub-three snapshot exceeds size limit or is empty")
    value = json.loads(data.decode("utf-8"), object_pairs_hook=reject_duplicate_properties)
    validate_sub3_snapshot(value)
    return data


def read_snapshot(path):
    with path.open("rb") as file:
        return validate_snapshot(file.read(MAX_BYTES + 1))


def collect_candidate(output):
    command = [sys.executable, str(SCRIPTS / "collect_marathon_sub3.py"),
               "--dry-run", "--output", str(output)]
    for option, variable in (
        ("--manifest", "RUN_AGENT_MARATHON_SUB3_MANIFEST"),
        ("--catalog-pdf", "RUN_AGENT_MARATHON_SUB3_CATALOG_PDF"),
        ("--announcement-dir", "RUN_AGENT_MARATHON_SUB3_ANNOUNCEMENT_DIR"),
        ("--announcement-fallback-dir", "RUN_AGENT_MARATHON_SUB3_ANNOUNCEMENT_FALLBACK_DIR"),
    ):
        raw = os.environ.get(variable)
        if not raw and option != "--manifest":
            continue
        require(raw, f"Configure {variable}")
        path = Path(raw)
        require(path.is_absolute(), f"{variable} must be an absolute path")
        exists = path.is_dir() if option.endswith("-dir") else path.is_file()
        require(exists, f"{variable} must point to an existing input")
        command.extend((option, str(path)))

    result = subprocess.run(command, stdout=subprocess.PIPE, check=True, timeout=COLLECTOR_TIMEOUT_SECONDS)
    return validate_snapshot(result.stdout)


def publish(path, data):
    atomic_publish(path, data)
    # Persist the previous snapshot's name before replacing the live snapshot.
    directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def refresh(dry_run, rollback):
    directory = output_directory()
    output = directory / SNAPSHOT_NAME
    previous = directory / PREVIOUS_NAME
    require_regular_path(directory / f".{output.name}.lock")

    # Share the live output lock with direct invocations of the collector.
    with write_lock(output):
        require_regular_path(output)
        require_regular_path(previous)
        if rollback:
            require(previous.is_file(), "No previous sub-three snapshot to restore")
            publish(output, read_snapshot(previous))
            print("Restored the previous sub-three snapshot.")
            return

        old = read_snapshot(output) if output.is_file() else None
        candidate = collect_candidate(output)
        if dry_run:
            print("Validated the candidate sub-three snapshot.")
        elif candidate == old:
            print("Sub-three snapshot already current.")
        else:
            if old is not None:
                publish(previous, old)
            publish(output, candidate)
            print("Published the sub-three snapshot.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group()
    action.add_argument("--dry-run", action="store_true", help="Validate the candidate without publishing")
    action.add_argument("--rollback", action="store_true", help="Restore the previous snapshot")
    args = parser.parse_args()
    try:
        refresh(args.dry_run, args.rollback)
    except subprocess.TimeoutExpired:
        parser.exit(1, f"Sub-three collector exceeded {COLLECTOR_TIMEOUT_SECONDS} seconds.\n")
    except subprocess.CalledProcessError as error:
        parser.exit(1, f"Sub-three collector exited with status {error.returncode}.\n")
    except BlockingIOError:
        parser.exit(1, "Another sub-three snapshot operation is running.\n")
    except (OSError, ValueError) as error:
        parser.exit(1, f"Sub-three snapshot operation failed: {error}\n")


if __name__ == "__main__":
    main()

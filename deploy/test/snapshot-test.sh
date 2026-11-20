#!/usr/bin/env bash
set -euo pipefail

deploy_dir=$(cd "$(dirname "$0")/.." && pwd)
workspace=$(mktemp -d)
trap 'rm -rf -- "$workspace"' EXIT
export RUN_AGENT_DEPLOY_DIR="$workspace/run-agent"
export RUN_AGENT_EXPORT_KEY_FILE="$workspace/opaque-id.key"
export RUN_AGENT_TEST_COMMAND_MV
RUN_AGENT_TEST_COMMAND_MV=$(command -v mv)
export PATH="$workspace/bin:$PATH"
mkdir -p "$workspace/bin"

if ! command -v flock > /dev/null; then
  # Sequential smoke tests also run on macOS, where flock may be unavailable.
  printf '#!/bin/sh\nexit 0\n' > "$workspace/bin/flock"
  chmod +x "$workspace/bin/flock"
fi

cat > "$workspace/source.json" <<'JSON'
[
  {"run_id":54321,"type":"Run","distance":5000.5,"moving_time":"0:29:00","start_date_local":"2025-01-02 08:03:01","summary_polyline":"sensitive-data","name":"sensitive-name"},
  {"run_id":9876,"type":"Ride"},
  {"run_id":123456,"type":"Run","distance":100,"moving_time":"1 day, 0:00:04","start_date_local":"2025-02-07T05:21:31+08:00"}
]
JSON

export_snapshot() {
  python3 "$deploy_dir/export-activities.py" "$1"
}

export_snapshot "$workspace/source.json" > "$workspace/first.json"
export_snapshot "$workspace/source.json" > "$workspace/repeat.json"
cmp "$workspace/first.json" "$workspace/repeat.json"
RUN_AGENT_EXPORT_KEY_FILE="$workspace/public-must-not-create-key" \
  python3 "$deploy_dir/export-activities.py" --public "$workspace/source.json" > "$workspace/public.json"
[[ ! -e "$workspace/public-must-not-create-key" ]]
python3 - "$workspace/public.json" <<'PY'
import json
import pathlib
import sys

activities = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert [row["run_id"] for row in activities] == [54321, 123456]
assert all(set(row) == {"run_id", "type", "distance", "moving_time", "start_date_local"} for row in activities)
assert [row["start_date_local"] for row in activities] == ["2025-01-02 00:00:00", "2025-02-07 00:00:00"]
assert [row["distance"] for row in activities] == [5000.5, 100]
assert [row["moving_time"] for row in activities] == ["0:29:00", "24:00:04"]
PY
python3 - "$workspace/first.json" "$RUN_AGENT_EXPORT_KEY_FILE" <<'PY'
import json
import pathlib
import stat
import sys

activities = json.loads(pathlib.Path(sys.argv[1]).read_text())
assert [row["start_date_local"] for row in activities] == ["2025-01-02 00:00:00", "2025-02-07 00:00:00"]
assert [row["distance"] for row in activities] == [5000.5, 100]
assert [row["moving_time"] for row in activities] == ["0:29:00", "24:00:04"]
assert all(set(row) == {"run_id", "type", "distance", "moving_time", "start_date_local"} for row in activities)
assert all(row["type"] == "Run" for row in activities)
assert all(type(row["run_id"]) is int and row["run_id"] not in {54321, 123456} for row in activities)
assert len({row["run_id"] for row in activities}) == 2
assert stat.S_IMODE(pathlib.Path(sys.argv[2]).stat().st_mode) == 0o600
PY

printf '[{"run_id":123456,"type":"Run","distance":100,"moving_time":"24:00:04","start_date_local":"2025-02-07"}]\n' > "$workspace/reordered.json"
export_snapshot "$workspace/reordered.json" > "$workspace/second.json"
python3 - "$workspace/first.json" "$workspace/second.json" <<'PY'
import json
import pathlib
import sys
first, second = (json.loads(pathlib.Path(path).read_text()) for path in sys.argv[1:])
assert first[1] == second[0], "Stable internal ID changed after reordering"
PY

for bad_fields in \
  '5000,"0:30:00","2025-02-30"' \
  '5000,"0:90:00","2025-02-01"' \
  '5000,"0:30:00","2025-02-01invalid"' \
  '5000,"10000000000000000:00:00","2025-02-01"' \
  '-1,"0:30:00","2025-02-01"'; do
  python3 - "$bad_fields" "$workspace/invalid.json" <<'PY'
import json
import pathlib
import sys
fields = json.loads(f"[{sys.argv[1]}]")
row = dict(zip(("distance", "moving_time", "start_date_local"), fields))
pathlib.Path(sys.argv[2]).write_text(json.dumps([{"run_id": 50, "type": "Run", **row}]))
PY
  if export_snapshot "$workspace/invalid.json" > "$workspace/rejected.json"; then
    printf 'Invalid running snapshot was accepted\n' >&2
    exit 1
  fi
  [[ ! -s "$workspace/rejected.json" ]]
done

sync_snapshot() {
  local path=$1 action=${2:-sync-data} checksum
  checksum=$(shasum -a 256 < "$path")
  { printf '%s\n' "${checksum%% *}"; cat "$path"; } | bash "$deploy_dir/remote-release.sh" "$action"
}

# GNU sha256sum is available on the server. Use shasum for the same check
# when running the test locally on macOS.
if ! command -v sha256sum > /dev/null; then
  printf '#!/bin/sh\nexec shasum -a 256 "$@"\n' > "$workspace/bin/sha256sum"
  chmod +x "$workspace/bin/sha256sum"
fi

rollback() {
  bash "$deploy_dir/remote-release.sh" rollback-data
}

active="$RUN_AGENT_DEPLOY_DIR/private-data/activities.json"
legacy="$RUN_AGENT_DEPLOY_DIR/data/activities.json"
mkdir -p "$(dirname "$legacy")"
cp "$workspace/first.json" "$legacy"
sync_snapshot "$workspace/first.json"
cmp "$active" "$workspace/first.json"
public_active="$RUN_AGENT_DEPLOY_DIR/public-data/activities.json"
sync_snapshot "$workspace/public.json" sync-public-data
cmp "$public_active" "$workspace/public.json"
bash "$deploy_dir/remote-release.sh" rollback-public-data
[[ ! -e "$public_active" ]]
cmp "$active" "$workspace/first.json"
rollback
[[ ! -e "$active" ]]

sync_snapshot "$workspace/first.json"
sync_snapshot "$workspace/second.json"
cmp "$active" "$workspace/second.json"
sync_snapshot "$workspace/second.json"
if { printf '%064d\n' 0; cat "$workspace/first.json"; } | bash "$deploy_dir/remote-release.sh" sync-data; then
  printf 'Checksum mismatch was accepted\n' >&2
  exit 1
fi
python3 -c 'import sys; sys.stdout.write(" " * (8 * 1024 * 1024 + 1))' > "$workspace/over-limit.json"
if sync_snapshot "$workspace/over-limit.json"; then
  printf 'Oversized upload was accepted\n' >&2
  exit 1
fi
cmp "$active" "$workspace/second.json"
rollback
cmp "$active" "$workspace/first.json"
if rollback; then
  printf 'An already-used rollback was accepted\n' >&2
  exit 1
fi
cmp "$active" "$workspace/first.json"

cmp "$legacy" "$workspace/first.json"

cat > "$workspace/bin/mv" <<'MV'
#!/usr/bin/env bash
set -euo pipefail
if [[ ${!#} == "${RUN_AGENT_TEST_MV_FAIL_DESTINATION:-}" ]]; then
  exit 44
fi
exec "$RUN_AGENT_TEST_COMMAND_MV" "$@"
MV
chmod +x "$workspace/bin/mv"

printf '[]\n' > "$workspace/third.json"
backup="$RUN_AGENT_DEPLOY_DIR/private-activities.previous.json"
sync_snapshot "$workspace/second.json"
export RUN_AGENT_TEST_MV_FAIL_DESTINATION="$active"
if sync_snapshot "$workspace/third.json"; then
  printf 'Interrupted snapshot installation unexpectedly succeeded\n' >&2
  exit 1
fi
cmp "$active" "$workspace/second.json"
cmp "$backup" "$workspace/first.json"
unset RUN_AGENT_TEST_MV_FAIL_DESTINATION
sync_snapshot "$workspace/third.json"
cmp "$active" "$workspace/third.json"
rollback
cmp "$active" "$workspace/second.json"

export RUN_AGENT_TEST_MV_FAIL_DESTINATION="$backup"
if sync_snapshot "$workspace/third.json"; then
  printf 'Interrupted backup rotation unexpectedly succeeded\n' >&2
  exit 1
fi
cmp "$active" "$workspace/third.json"
unset RUN_AGENT_TEST_MV_FAIL_DESTINATION
rollback
cmp "$active" "$workspace/second.json"

printf 'Activity snapshot tests passed\n'

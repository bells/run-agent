#!/usr/bin/env bash
set -euo pipefail

deploy_dir=$(cd "$(dirname "$0")/.." && pwd)
workspace=$(mktemp -d)
trap 'rm -rf "$workspace"' EXIT
export RUN_AGENT_DEPLOY_DIR="$workspace/releases"
export RUN_AGENT_TEST_LOG="$workspace/docker.log"
export RUN_AGENT_TEST_FAIL="$workspace/failing-image"
export PATH="$workspace/bin:$PATH"
state="$RUN_AGENT_DEPLOY_DIR"
mkdir -p "$workspace/bin" "$state"
touch "$state/provider.env"
chmod 600 "$state/provider.env"

cat > "$workspace/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
set -eu
case "$1" in
  build)
    printf '%s\n' "$*" >> "$RUN_AGENT_TEST_LOG.build"
    exit 0 ;;
  info|ps) exit 0 ;;
  compose)
    while [[ $1 != --env-file ]]; do shift; done
    source "$2"
    shift 2
    for argument in "$@"; do
      case "$argument" in
        up|down)
          printf '%s %s\n' "$argument" "$RUN_AGENT_IMAGE" >> "$RUN_AGENT_TEST_LOG"
          printf '%s\n' "$RUN_AGENT_DATA_DIR" >> "$RUN_AGENT_TEST_LOG.mount"
          if [[ "$argument" == up && -f "$RUN_AGENT_TEST_FAIL" ]]; then
            ! grep -Fxq "$RUN_AGENT_IMAGE" "$RUN_AGENT_TEST_FAIL"
          fi
          exit
          ;;
      esac
    done
    ;;
esac
exit 1
DOCKER
if ! command -v flock >/dev/null; then
  # These tests are sequential. Linux uses the actual flock utility.
  printf '#!/bin/sh\nexit 0\n' > "$workspace/bin/flock"
  chmod +x "$workspace/bin/flock"
fi
chmod +x "$workspace/bin/docker"

release_a=aaaaaaaaaaaa-001122334455
release_b=bbbbbbbbbbbb-001122334455
release_c=cccccccccccc-001122334455
release_initial_failure=dddddddddddd-001122334455
release_mcp=eeeeeeeeeeee-001122334455

bundle() {
  local release=$1
  local stage="$workspace/$release"
  mkdir "$stage"
  printf '%s\n' "$release" > "$stage/release-id"
  printf '%s%028d\n' "${release:0:12}" 0 > "$stage/run-agent-revision"
  touch "$stage/compose.yaml" "$stage/Dockerfile" "$stage/run-agent.jar"
  tar -C "$stage" -cf "$stage/bundle.tar" release-id run-agent-revision compose.yaml Dockerfile run-agent.jar
}

assert_state() {
  local current=$1 previous=$2
  [[ $(sed -n '1p' "$state/active") == "$current" ]]
  [[ $(sed -n '2p' "$state/active") == "$previous" ]]
  [[ ! -e "$state/pending" ]]
}

deploy() {
  bash "$deploy_dir/remote-release.sh" deploy < "$workspace/$1/bundle.tar"
}

for release in "$release_a" "$release_b" "$release_c" "$release_initial_failure"; do bundle "$release"; done

printf 'run-agent:%s\n' "$release_initial_failure" > "$RUN_AGENT_TEST_FAIL"
if deploy "$release_initial_failure"; then
  printf 'Unhealthy initial release unexpectedly succeeded\n' >&2
  exit 1
fi
[[ ! -e "$state/active" && ! -e "$state/pending" ]]
[[ $(tail -n 1 "$RUN_AGENT_TEST_LOG") == "down run-agent:$release_initial_failure" ]]

printf 'run-agent:%s\n' "$release_b" > "$RUN_AGENT_TEST_FAIL"
deploy "$release_a"
assert_state "$release_a" ""
revision=$(cat "$workspace/$release_a/run-agent-revision")
[[ $(cat "$state/releases/$release_a/run-agent-revision") == "$revision" ]]
grep -q "org.opencontainers.image.revision=$revision" "$RUN_AGENT_TEST_LOG.build"
# Installed legacy releases have no full revision file, and may still be restored.
rm -- "$state/releases/$release_a/run-agent-revision"
if deploy "$release_b"; then
  printf 'Unhealthy update unexpectedly succeeded\n' >&2
  exit 1
fi
assert_state "$release_a" ""
[[ $(tail -n 1 "$RUN_AGENT_TEST_LOG") == "up run-agent:$release_a" ]]

# Simulate a lost deployment session after recording the candidate.
printf '%s\n' "$release_b" > "$state/pending"
: > "$RUN_AGENT_TEST_LOG"
deploy "$release_c"
assert_state "$release_c" "$release_a"
[[ $(head -n 1 "$RUN_AGENT_TEST_LOG") == "up run-agent:$release_a" ]]
bash "$deploy_dir/remote-release.sh" rollback
assert_state "$release_a" "$release_c"

bundle "$release_mcp"
mcp_stage="$workspace/$release_mcp"
touch "$mcp_stage/compose.mcp.yaml" "$mcp_stage/running-mcp.jar"
printf '0123456789abcdef0123456789abcdef01234567\n' > "$mcp_stage/mcp-revision"
tar -C "$mcp_stage" -cf - release-id run-agent-revision compose.yaml Dockerfile run-agent.jar compose.mcp.yaml \
  running-mcp.jar mcp-revision | bash "$deploy_dir/remote-release.sh" deploy-mcp
assert_state "$release_mcp" "$release_a"
[[ $(tail -n 1 "$RUN_AGENT_TEST_LOG.mount") == "$state/private-data" ]]
printf '[{"type":"Run"}]\n' > "$state/private-data/activities.json"
grep -Fx "RUN_AGENT_MCP_IMAGE=running-mcp-server:$release_mcp" "$state/releases/$release_mcp/release.env"
mcp_revision=$(cat "$mcp_stage/mcp-revision")
[[ $(cat "$state/releases/$release_mcp/mcp-revision") == "$mcp_revision" ]]
grep -q "org.opencontainers.image.revision=$mcp_revision" "$RUN_AGENT_TEST_LOG.build"
bash "$deploy_dir/remote-release.sh" rollback
assert_state "$release_a" "$release_mcp"
[[ $(tail -n 1 "$RUN_AGENT_TEST_LOG.mount") == "$state/data" ]]
[[ ! -e "$state/data/activities.json" ]]
[[ -f "$state/private-data/activities.json" ]]

printf 'Release and rollback tests passed\n'

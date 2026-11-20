#!/usr/bin/env bash
set -euo pipefail
umask 077

release_root=${RUN_AGENT_DEPLOY_DIR:-$HOME/.local/share/run-agent}
releases="$release_root/releases"
legacy_data_dir="$release_root/data"
private_data_dir="$release_root/private-data"
public_data_dir="$release_root/public-data"
project=run-agent-private
stage=
docker_sudo=false
trap 'if [[ -n $stage ]]; then rm -rf -- "$stage"; fi' EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

fail() {
  printf '%s\n' "$*" >&2
  exit 1
}

docker() {
  if [[ $docker_sudo == true ]]; then
    command sudo -n --preserve-env=RUN_AGENT_PROVIDER_FILE,RUN_AGENT_PRIVATE_FILE,RUN_AGENT_DATA_DIR,RUN_AGENT_PUBLIC_DATA_DIR,RUN_AGENT_KNOWLEDGE_DIR docker "$@"
  else
    command docker "$@"
  fi
}

docker_platform() {
  local platform
  platform=$(docker info --format '{{.OSType}}/{{.Architecture}}')
  case "$platform" in
    linux/x86_64|linux/amd64) printf 'linux/amd64\n' ;;
    linux/aarch64|linux/arm64) printf 'linux/arm64\n' ;;
    *) fail "Unsupported Docker platform: $platform" ;;
  esac
}

valid_release() {
  [[ $1 =~ ^[a-f0-9]{12}-[a-f0-9]{12}$ ]]
}

compose() {
  local release=$1
  shift
  valid_release "$release" || fail "Invalid release ID."
  local directory="$releases/$release"
  local -x RUN_AGENT_DATA_DIR="$legacy_data_dir"
  local options=(--project-name "$project")
  if [[ -f $release_root/runtime.env ]]; then
    options+=(--env-file "$release_root/runtime.env")
  fi
  options+=(--env-file "$directory/release.env" -f "$directory/compose.yaml")
  if [[ -f $directory/compose.mcp.yaml ]]; then
    options+=(-f "$directory/compose.mcp.yaml")
    mkdir -p -m 755 -- "$RUN_AGENT_PUBLIC_DATA_DIR" "$RUN_AGENT_KNOWLEDGE_DIR"
    if [[ -f $directory/private-data-compatible ]]; then
      RUN_AGENT_DATA_DIR="$private_data_dir"
    fi
  fi
  prepare_data_directory || return
  docker compose "${options[@]}" "$@"
}

activate() {
  compose "$1" up --detach --remove-orphans --wait --wait-timeout 90
}

record() {
  local name=$1
  shift
  printf '%s\n' "$@" > "$release_root/$name.new"
  mv -f -- "$release_root/$name.new" "$release_root/$name"
}

restore_pending() {
  local pending
  pending=$(cat "$release_root/pending")
  if [[ -n $current ]]; then
    activate "$current" || fail "Could not restore $current. Recovery will retry on the next run."
  else
    compose "$pending" down || fail "Could not remove the unsuccessful first release."
  fi
  rm -- "$release_root/pending"
}

switch_to() {
  local candidate=$1
  record pending "$candidate"
  if activate "$candidate"; then
    record active "$candidate" "$current"
    rm -- "$release_root/pending"
    printf 'Healthy release: %s\n' "$candidate"
  else
    printf 'Activation failed for %s. Restoring the previous state.\n' "$candidate" >&2
    restore_pending
    return 1
  fi
}

prepare_data_directory() {
  mkdir -p -- "$release_root"
  if [[ ! -d $RUN_AGENT_DATA_DIR ]]; then
    mkdir -p -m 755 -- "$RUN_AGENT_DATA_DIR"
  fi
}

export RUN_AGENT_PROVIDER_FILE="$release_root/provider.env"
export RUN_AGENT_PRIVATE_FILE="$release_root/private-chat.env"
export RUN_AGENT_DATA_DIR="$legacy_data_dir"
export RUN_AGENT_PUBLIC_DATA_DIR="$public_data_dir"
export RUN_AGENT_KNOWLEDGE_DIR="$release_root/knowledge"

finish_snapshot_update() {
  if [[ -f $pending_snapshot/activities.json ]]; then
    mv -f -- "$pending_snapshot/activities.json" "$active_snapshot"
  fi
  if [[ -f $pending_snapshot/previous ]]; then
    mv -f -- "$pending_snapshot/previous" "$previous_snapshot"
  fi
  rmdir -- "$pending_snapshot"
}

if [[ ${1:-} =~ ^(sync|rollback)-(public-)?data$ ]]; then
  data_scope=private
  if [[ $1 == *-public-data ]]; then data_scope=public; fi
  RUN_AGENT_DATA_DIR="$release_root/$data_scope-data"
  prepare_data_directory
  lockfile="$release_root/data.lock"
  if [[ $data_scope == public ]]; then lockfile="$release_root/public-data.lock"; fi
  exec 8>"$lockfile"
  flock -n 8 || fail "Another data snapshot operation is running."

  active_snapshot="$RUN_AGENT_DATA_DIR/activities.json"
  previous_snapshot="$release_root/$data_scope-activities.previous.json"
  pending_snapshot="$RUN_AGENT_DATA_DIR/.pending-snapshot"
  if [[ -d $pending_snapshot ]]; then
    printf 'Recovering an interrupted activity update.\n' >&2
    finish_snapshot_update
  fi
  stage=$(mktemp -d "$RUN_AGENT_DATA_DIR/.import.XXXXXXXX")
  if [[ $1 == rollback-* ]]; then
    [[ -f $previous_snapshot ]] || fail "No previous activity snapshot is available."
    if [[ -s $previous_snapshot ]]; then
      cp -- "$previous_snapshot" "$stage/activities.json"
      chmod 644 "$stage/activities.json"
      mv -f -- "$stage/activities.json" "$active_snapshot"
    else
      rm -f -- "$active_snapshot"
    fi
    rm -- "$previous_snapshot"
    printf 'Activity snapshot restored.\n'
    exit
  fi

  read -r expected
  [[ $expected =~ ^[a-f0-9]{64}$ ]] || fail "Invalid activity export checksum."
  max_snapshot_bytes=$((8 * 1024 * 1024))
  head -c "$((max_snapshot_bytes + 1))" > "$stage/activities.json"
  [[ -s $stage/activities.json ]] || fail "Empty activity export."
  (( $(wc -c < "$stage/activities.json") <= max_snapshot_bytes )) \
    || fail "Activity export exceeds 8 MiB."
  checksum=$(sha256sum < "$stage/activities.json")
  [[ ${checksum%% *} == "$expected" ]] || fail "Incomplete activity export."
  if cmp -s "$stage/activities.json" "$active_snapshot"; then
    printf 'Activity snapshot already current.\n'
    exit
  fi

  # An empty backup represents the state before the first snapshot was installed.
  if [[ -f $active_snapshot ]]; then
    cp -- "$active_snapshot" "$stage/previous"
    chmod 600 "$stage/previous"
  else
    : > "$stage/previous"
  fi
  chmod 644 "$stage/activities.json"
  # Publishing the complete candidate and backup first lets the next operation
  # finish either rename even when the SSH session stops between them.
  mv -- "$stage" "$pending_snapshot"
  stage=
  finish_snapshot_update
  printf 'Activity snapshot updated.\n'
  exit
fi

if ! command docker info --format '{{.ServerVersion}}' > /dev/null 2>&1; then
  docker_sudo=true
fi

case "${1:-}" in
  check)
    . /etc/os-release
    printf '%s\n' "$PRETTY_NAME"
    free -m
    df -h "$HOME"
    docker_platform
    docker compose version
    exit ;;
  platform)
    docker_platform
    exit ;;
  deploy|deploy-mcp|rollback) ;;
  *) fail "Expected check, platform, deploy, deploy-mcp or rollback." ;;
esac

unset RUN_AGENT_IMAGE RUN_AGENT_MCP_IMAGE
[[ -f $RUN_AGENT_PROVIDER_FILE && -O $RUN_AGENT_PROVIDER_FILE && ! -L $RUN_AGENT_PROVIDER_FILE ]] \
  || fail "Create an owned provider.env at $RUN_AGENT_PROVIDER_FILE before deploying."

mkdir -p -- "$releases"
prepare_data_directory

exec 9>"$release_root/deploy.lock"
flock -n 9 || fail "Another release operation is running."

current=
previous=
if [[ -f $release_root/active ]]; then
  {
    read -r current
    read -r previous
  } < "$release_root/active"
  valid_release "$current" || fail "Invalid active release state."
fi
if [[ -f $release_root/pending ]]; then
  printf 'Recovering an interrupted activation.\n' >&2
  restore_pending
fi

if [[ $1 == rollback ]]; then
  [[ -n $previous ]] || fail "No previous healthy release is available."
  switch_to "$previous"
  exit
fi

if [[ -z $current && -n $(docker ps -aq --filter "label=com.docker.compose.project=$project") ]]; then
  fail "The Compose project already exists without release state."
fi

stage=$(mktemp -d "$releases/.upload.XXXXXXXX")
bundle_files=(release-id run-agent-revision compose.yaml Dockerfile run-agent.jar)
if [[ $1 == deploy-mcp ]]; then
  bundle_files+=(compose.mcp.yaml running-mcp.jar mcp-revision)
fi
tar -xf - -C "$stage" "${bundle_files[@]}"
candidate=$(cat "$stage/release-id")
valid_release "$candidate" || fail "Invalid incoming release ID."
[[ ! -e $releases/$candidate ]] || fail "Release $candidate already exists."
revision=$(cat "$stage/run-agent-revision")
[[ $revision =~ ^[a-f0-9]{40}$ && $candidate == "${revision:0:12}-"* ]] \
  || fail "Invalid RunAgent source revision."

image="run-agent:$candidate"
docker build --tag "$image" --label "org.opencontainers.image.version=$candidate" \
  --label "org.opencontainers.image.revision=$revision" "$stage"
printf 'RUN_AGENT_IMAGE=%s\n' "$image" > "$stage/release.env"
if [[ $1 == deploy-mcp ]]; then
  mcp_revision=$(cat "$stage/mcp-revision")
  [[ $mcp_revision =~ ^[a-f0-9]{40}$ ]] || fail "Invalid MCP source revision."
  mcp_image="running-mcp-server:$candidate"
  docker build --tag "$mcp_image" --build-arg APP_JAR=running-mcp.jar \
    --label "org.opencontainers.image.revision=$mcp_revision" "$stage"
  printf 'RUN_AGENT_MCP_IMAGE=%s\n' "$mcp_image" >> "$stage/release.env"
  rm -- "$stage/running-mcp.jar"
  touch "$stage/private-data-compatible"
fi
rm -- "$stage/run-agent.jar" "$stage/Dockerfile"
mv -- "$stage" "$releases/$candidate"
stage=
switch_to "$candidate"

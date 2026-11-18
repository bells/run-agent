#!/usr/bin/env bash
set -euo pipefail
umask 077

release_root=${RUN_AGENT_DEPLOY_DIR:-$HOME/.local/share/run-agent}
releases="$release_root/releases"
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
    command sudo -n --preserve-env=RUN_AGENT_PROVIDER_FILE,RUN_AGENT_PRIVATE_FILE,RUN_AGENT_DATA_DIR docker "$@"
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
  docker compose --project-name "$project" --env-file "$directory/release.env" \
    -f "$directory/compose.yaml" "$@"
}

activate() {
  compose "$1" up --detach --wait --wait-timeout 75
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
export RUN_AGENT_DATA_DIR="$release_root/data"
if [[ ${1:-} == sync-data ]]; then
  read -r expected
  [[ $expected =~ ^[a-f0-9]{64}$ ]] || fail "Invalid activity export checksum."
  prepare_data_directory
  stage=$(mktemp -d "$RUN_AGENT_DATA_DIR/.import.XXXXXXXX")
  cat > "$stage/activities.json"
  checksum=$(sha256sum < "$stage/activities.json")
  [[ ${checksum%% *} == "$expected" ]] || fail "Incomplete activity export."
  chmod 644 "$stage/activities.json"
  mv -f -- "$stage/activities.json" "$RUN_AGENT_DATA_DIR/activities.json"
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
  deploy|rollback) ;;
  *) fail "Expected check, platform, deploy or rollback." ;;
esac

unset RUN_AGENT_IMAGE
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
tar -xf - -C "$stage" release-id compose.yaml Dockerfile run-agent.jar
candidate=$(cat "$stage/release-id")
valid_release "$candidate" || fail "Invalid incoming release ID."
[[ ! -e $releases/$candidate ]] || fail "Release $candidate already exists."

image="run-agent:$candidate"
docker build --tag "$image" --label "org.opencontainers.image.version=$candidate" "$stage"
rm -- "$stage/run-agent.jar" "$stage/Dockerfile"
printf 'RUN_AGENT_IMAGE=%s\n' "$image" > "$stage/release.env"
mv -- "$stage" "$releases/$candidate"
stage=
switch_to "$candidate"

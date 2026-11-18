#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage:
  deploy/deploy.sh check <ssh-target>
  deploy/deploy.sh deploy <ssh-target> [git-ref]
  deploy/deploy.sh rollback <ssh-target>
  deploy/deploy.sh sync-data <ssh-target> <activities.json>

Use an SSH alias or a user@host. Set RUN_AGENT_SSH_KNOWN_HOSTS to use a
separately verified known_hosts file.
USAGE
  exit 2
}

mode=${1:-}
target=${2:-}
[[ -n $target && $# -le 3 ]] || usage

deploy_dir=$(cd "$(dirname "$0")" && pwd)
encoded_script=$(base64 < "$deploy_dir/remote-release.sh" | tr -d '\n')

remote() {
  local action=$1
  local command="bash -c \"\$(printf '%s' '$encoded_script' | base64 -d)\" run-agent-release $action"
  if [[ -n ${RUN_AGENT_SSH_KNOWN_HOSTS:-} ]]; then
    set -- -o "UserKnownHostsFile=$RUN_AGENT_SSH_KNOWN_HOSTS"
  else
    set --
  fi
  ssh -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10 \
    "$@" -- "$target" "$command"
}

case "$mode" in
  check|rollback)
    [[ $# == 2 ]] || usage
    remote "$mode"
    exit ;;
  deploy) ;;
  sync-data)
    [[ $# == 3 ]] || usage
    snapshot=$(mktemp)
    trap 'rm -f -- "$snapshot"' EXIT
    python3 "$deploy_dir/export-activities.py" "$3" > "$snapshot"
    checksum=$(shasum -a 256 < "$snapshot")
    { printf '%s\n' "${checksum%% *}"; cat "$snapshot"; } | remote sync-data
    exit ;;
  *) usage ;;
esac

platform=$(remote platform)
case "$platform" in
  linux/amd64|linux/arm64) ;;
  *) printf 'Invalid target platform: %s\n' "$platform" >&2; exit 1 ;;
esac

repo_dir=$(git -C "$deploy_dir" rev-parse --show-toplevel)
revision=$(git -C "$repo_dir" rev-parse --verify --end-of-options "${3:-HEAD}^{commit}")
release="${revision:0:12}-$(openssl rand -hex 6)"
workspace=$(mktemp -d)
trap 'rm -rf -- "$workspace"' EXIT
mkdir "$workspace/source" "$workspace/release"

git -C "$repo_dir" archive "$revision" | tar -xf - -C "$workspace/source"
(
  cd "$workspace/source"
  ./gradlew --no-daemon build
)
jar=
for candidate in "$workspace/source"/build/libs/*.jar; do
  [[ -f $candidate && $candidate != *-plain.jar ]] || continue
  [[ -z $jar ]] || { printf 'More than one executable JAR found.\n' >&2; exit 1; }
  jar=$candidate
done
[[ -n $jar ]] || { printf 'No executable JAR found.\n' >&2; exit 1; }
cp "$jar" "$workspace/release/run-agent.jar"
cp "$deploy_dir/Dockerfile" "$deploy_dir/compose.yaml" "$workspace/release/"
printf '%s\n' "$release" > "$workspace/release/release-id"

tar -C "$workspace/release" -cf - release-id compose.yaml Dockerfile run-agent.jar | remote deploy

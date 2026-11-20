#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'USAGE'
Usage:
  deploy/deploy.sh check <ssh-target>
  deploy/deploy.sh deploy <ssh-target> [git-ref]
  deploy/deploy.sh deploy-mcp <ssh-target> <running-mcp-server-repo> [run-agent-ref] [mcp-ref]
  deploy/deploy.sh rollback <ssh-target>
  deploy/deploy.sh sync-data <ssh-target> <activities.json>
  deploy/deploy.sh rollback-data <ssh-target>
  deploy/deploy.sh sync-public-data <ssh-target> <published-activities.json>
  deploy/deploy.sh rollback-public-data <ssh-target>

Use an SSH alias or a user@host. Set RUN_AGENT_SSH_KNOWN_HOSTS to use a
separately verified known_hosts file.
USAGE
  exit 2
}

mode=${1:-}
target=${2:-}
[[ -n $target && $# -le 5 ]] || usage

deploy_dir=$(cd "$(dirname "$0")" && pwd)
encode() {
  base64 | tr -d '\n'
}

remote() {
  local action=$1
  local command="bash -c \"\$(printf '%s' '$encoded_script' | base64 -d)\" run-agent-release $action"
  if [[ -n ${RUN_AGENT_SSH_KNOWN_HOSTS:-} ]]; then
    set -- -o "UserKnownHostsFile=$RUN_AGENT_SSH_KNOWN_HOSTS"
  else
    set --
  fi
  ssh -o BatchMode=yes -o StrictHostKeyChecking=yes -o ConnectTimeout=10 \
    -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
    "$@" -- "$target" "$command"
}

case "$mode" in
  check|rollback|rollback-data|rollback-public-data)
    [[ $# == 2 ]] || usage
    encoded_script=$(encode < "$deploy_dir/remote-release.sh")
    remote "$mode"
    exit ;;
  deploy)
    [[ $# -le 3 ]] || usage
    ref=${3:-HEAD}
    ;;
  deploy-mcp)
    [[ $# -ge 3 ]] || usage
    mcp_repo=$(git -C "$3" rev-parse --show-toplevel)
    mcp_revision=$(git -C "$mcp_repo" rev-parse --verify --end-of-options "${5:-HEAD}^{commit}")
    ref=${4:-HEAD}
    ;;
  sync-data|sync-public-data)
    [[ $# == 3 ]] || usage
    snapshot=$(mktemp)
    trap 'rm -f -- "$snapshot"' EXIT
    export_options=()
    if [[ $mode == sync-public-data ]]; then export_options+=(--public); fi
    python3 "$deploy_dir/export-activities.py" "${export_options[@]}" "$3" > "$snapshot"
    encoded_script=$(encode < "$deploy_dir/remote-release.sh")
    checksum=$(shasum -a 256 < "$snapshot")
    { printf '%s\n' "${checksum%% *}"; cat "$snapshot"; } | remote "$mode"
    exit ;;
  *) usage ;;
esac

repo_dir=$(git -C "$deploy_dir" rev-parse --show-toplevel)
revision=$(git -C "$repo_dir" rev-parse --verify --end-of-options "$ref^{commit}")
encoded_script=$(git -C "$repo_dir" show "$revision:deploy/remote-release.sh" | encode)
platform=$(remote platform)
case "$platform" in
  linux/amd64|linux/arm64) ;;
  *) printf 'Invalid target platform: %s\n' "$platform" >&2; exit 1 ;;
esac

release="${revision:0:12}-$(openssl rand -hex 6)"
workspace=$(mktemp -d)
trap 'rm -rf -- "$workspace"' EXIT
mkdir "$workspace/release"

build_jar() {
  local repository=$1 commit=$2 name=$3
  local source="$workspace/$name"
  mkdir "$source"
  git -C "$repository" archive "$commit" | tar -xf - -C "$source"
  (cd "$source" && ./gradlew --no-daemon build)
  local jar= candidate
  for candidate in "$source"/build/libs/*.jar; do
    [[ -f $candidate && $candidate != *-plain.jar ]] || continue
    [[ -z $jar ]] || { printf 'More than one executable JAR found.\n' >&2; exit 1; }
    jar=$candidate
  done
  [[ -n $jar ]] || { printf 'No executable JAR found.\n' >&2; exit 1; }
  cp "$jar" "$workspace/release/$name.jar"
}

build_jar "$repo_dir" "$revision" run-agent
source_deploy="$workspace/run-agent/deploy"
cp "$source_deploy/Dockerfile" "$source_deploy/compose.yaml" "$workspace/release/"
printf '%s\n' "$release" > "$workspace/release/release-id"
printf '%s\n' "$revision" > "$workspace/release/run-agent-revision"
bundle=(release-id run-agent-revision compose.yaml Dockerfile run-agent.jar)

if [[ $mode == deploy-mcp ]]; then
  build_jar "$mcp_repo" "$mcp_revision" running-mcp
  printf '%s\n' "$mcp_revision" > "$workspace/release/mcp-revision"
  cp "$source_deploy/compose.mcp.yaml" "$workspace/release/"
  tar -C "$workspace/release" -cf - "${bundle[@]}" \
    compose.mcp.yaml running-mcp.jar mcp-revision | remote deploy-mcp
else
  tar -C "$workspace/release" -cf - "${bundle[@]}" | remote deploy
fi

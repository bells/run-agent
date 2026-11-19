#!/usr/bin/env bash
set -euo pipefail

deploy_dir=$(cd "$(dirname "$0")/.." && pwd)
workspace=$(mktemp -d)
trap 'rm -rf -- "$workspace"' EXIT
export RUN_AGENT_TEST_PACKAGE="$workspace/package.tar"
export RUN_AGENT_TEST_HELPER="$workspace/helper.log"
export PATH="$workspace/bin:$PATH"
mkdir -p "$workspace/bin"

cat > "$workspace/bin/ssh" <<'SSH'
#!/usr/bin/env bash
set -euo pipefail
bash -c "${!#}"
SSH
chmod +x "$workspace/bin/ssh"

init_repository() {
  mkdir -p "$1"
  git -C "$1" init -q
  git -C "$1" config user.name "Release Test"
  git -C "$1" config user.email "release@example.invalid"
  git -C "$1" config commit.gpgsign false
  cat > "$1/gradlew" <<'GRADLE'
#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/libs
cp build-marker build/libs/fixture.jar
GRADLE
  chmod +x "$1/gradlew"
}

commit_fixture() {
  git -C "$1" add .
  git -C "$1" commit -qm "$2"
  git -C "$1" rev-parse HEAD
}

backend="$workspace/backend"
mcp="$workspace/mcp"
init_repository "$backend"
init_repository "$mcp"
mkdir "$backend/deploy"
cp "$deploy_dir/deploy.sh" "$backend/deploy/"
cat > "$backend/deploy/remote-release.sh" <<'REMOTE'
#!/usr/bin/env bash
set -euo pipefail
printf 'selected %s\n' "$1" >> "$RUN_AGENT_TEST_HELPER"
case "$1" in
  platform) printf 'linux/amd64\n' ;;
  deploy|deploy-mcp) cat > "$RUN_AGENT_TEST_PACKAGE" ;;
  *) exit 1 ;;
esac
REMOTE
printf 'selected app\n' > "$backend/build-marker"
printf 'selected Dockerfile\n' > "$backend/deploy/Dockerfile"
printf 'selected compose\n' > "$backend/deploy/compose.yaml"
printf 'selected MCP compose\n' > "$backend/deploy/compose.mcp.yaml"
backend_revision=$(commit_fixture "$backend" "Selected app release")
printf 'dirty Dockerfile\n' > "$backend/deploy/Dockerfile"
printf 'dirty compose\n' > "$backend/deploy/compose.yaml"
printf 'dirty MCP compose\n' > "$backend/deploy/compose.mcp.yaml"
sed 's/selected/dirty/' "$backend/deploy/remote-release.sh" > "$workspace/dirty-helper"
mv "$workspace/dirty-helper" "$backend/deploy/remote-release.sh"

printf 'selected MCP\n' > "$mcp/build-marker"
mcp_revision=$(commit_fixture "$mcp" "Selected MCP release")
printf 'HEAD MCP\n' > "$mcp/build-marker"
mcp_head=$(commit_fixture "$mcp" "Later MCP release")

entry() {
  tar -xOf "$RUN_AGENT_TEST_PACKAGE" "$1"
}

assert_entry() {
  if [[ $(entry "$1") != "$2" ]]; then
    printf 'The release archive has incorrect content for %s\n' "$1" >&2
    return 1
  fi
}

"$backend/deploy/deploy.sh" deploy test-fixture "$backend_revision"
assert_entry run-agent.jar "selected app"
assert_entry Dockerfile "selected Dockerfile"
assert_entry compose.yaml "selected compose"
assert_entry run-agent-revision "$backend_revision"
[[ $(entry release-id) == "${backend_revision:0:12}-"* ]]
[[ $(cat "$RUN_AGENT_TEST_HELPER") == $'selected platform\nselected deploy' ]]

"$backend/deploy/deploy.sh" deploy-mcp test-fixture "$mcp" "$backend_revision" "$mcp_revision"
assert_entry run-agent-revision "$backend_revision"
assert_entry running-mcp.jar "selected MCP"
assert_entry mcp-revision "$mcp_revision"
assert_entry compose.mcp.yaml "selected MCP compose"

"$backend/deploy/deploy.sh" deploy-mcp test-fixture "$mcp" "$backend_revision"
assert_entry running-mcp.jar "HEAD MCP"
assert_entry mcp-revision "$mcp_head"

printf 'Release bundle tests passed\n'

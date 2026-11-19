#!/usr/bin/env bash
set -euo pipefail

site=${RUN_AGENT_PUBLIC_SITE:-https://run.watsonzhu.cn}
origin=${RUN_AGENT_PUBLIC_ORIGIN:-https://run-api.watsonzhu.cn}

check() {
  local expected=$1 url=$2 actual
  shift 2
  actual=$(curl --silent --show-error --max-time 15 --output /dev/null --write-out '%{http_code}' "$@" "$url")
  printf '%s %s\n' "$actual" "$url"
  [[ $actual == "$expected" ]]
}

check 200 "$site/"
check 200 "$site/summary"
for base in "$site" "$origin"; do
  check 401 "$base/api/v1/chat" -H 'Content-Type: application/json' --data '{"message":"health probe"}'
  check 401 "$base/api/v1/training/stats" -H 'Content-Type: application/json' --data '{"kind":"PERSONAL_BEST","distanceType":"FIVE_K"}'
done

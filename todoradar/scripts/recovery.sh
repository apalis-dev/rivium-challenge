#!/usr/bin/env bash
# Kill the engine mid-stage ($3 = download|scan), restart it, and check the scan resumes.
set -u
HERE=$(cd "$(dirname "$0")/.." && pwd)
BIN=$HERE/rust/target/debug/radar-core$( [ -f "$HERE/rust/target/debug/radar-core.exe" ] && echo .exe)
ROOT=$HERE/rust/dev-root
H=(-s -H "X-Radar-Token: dev")
URL=http://127.0.0.1:8737
OWNER=${1:-nushell}; NAME=${2:-nushell}; STAGE=${3:-download}

start() {
  TODO_RADAR_API_TOKEN=dev TODO_RADAR_GITHUB_TOKEN="$(gh auth token)" TODO_RADAR_CLEANUP_DELAY_SECS=600 \
    "$BIN" --root "$ROOT" >> "$ROOT/run.log" 2>&1 &
  for _ in $(seq 1 40); do curl -s -m 1 $URL/health >/dev/null && return; sleep 0.25; done
}
stop() { pkill -f radar-core 2>/dev/null || taskkill //F //IM radar-core.exe >/dev/null 2>&1; sleep 0.5; }
field() { python -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
show() { curl "${H[@]}" $URL/scans/$1 | field "'$2', d['state'], d['stage'], 'bytes', d['bytes_done'], '/', d['bytes_total'], 'entries', d['entries_done'], '/', d['entries_total'], 'todos', d['todo_count'], 'attempts', d['attempts']"; }

stop; rm -rf "$ROOT"; mkdir -p "$ROOT"; start
id=$(curl "${H[@]}" -X POST $URL/scans -H 'content-type: application/json' -d "{\"owner\":\"$OWNER\",\"repo\":\"$NAME\"}" | field "d['id']")
for _ in $(seq 1 600); do
  st=$(curl "${H[@]}" $URL/scans/$id | field "d['stage'], d['bytes_done'], d['entries_done']")
  set -- $st
  if [ "$STAGE" = download ] && [ "$1" = download ] && [ "$2" -gt 2000000 ]; then break; fi
  if [ "$STAGE" = scan ] && [ "$1" = scan ] && [ "$3" -gt 0 ]; then break; fi
  sleep 0.1
done
show $id "at kill   "
stop
ls -la "$ROOT/cache"
start
t0=$(date +%s)
sleep 1; show $id "restarted "
for _ in $(seq 1 600); do
  st=$(curl "${H[@]}" $URL/scans/$id | field "d['state']")
  [ "$st" = done ] || [ "$st" = failed ] && break
  sleep 0.5
done
show $id "final     "
echo "finished $(( $(date +%s) - t0 ))s after restart"
grep -c "Re-enqueued\|reenqueue" "$ROOT/run.log"
stop

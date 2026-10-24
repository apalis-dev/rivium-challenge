#!/usr/bin/env bash
# End-to-end check of the engine on the laptop: auth, repos, scan, diff, report, keep/cleanup,
# and crash recovery (kill mid-scan, restart, scan resumes).
set -u
HERE=$(cd "$(dirname "$0")/.." && pwd)
BIN=$HERE/rust/target/debug/radar-core$( [ -f "$HERE/rust/target/debug/radar-core.exe" ] && echo .exe)
ROOT=$HERE/rust/dev-root
H=(-s -H "X-Radar-Token: dev")
URL=http://127.0.0.1:8737
REPO_OWNER=${1:-rivium-rs}
REPO_NAME=${2:-rivium}

start() {
  TODO_RADAR_API_TOKEN=dev TODO_RADAR_GITHUB_TOKEN="$(gh auth token)" TODO_RADAR_CLEANUP_DELAY_SECS=20 \
    "$BIN" --root "$ROOT" >> "$ROOT/run.log" 2>&1 &
  for _ in $(seq 1 40); do curl -s -m 1 $URL/health >/dev/null && return; sleep 0.25; done
  echo "engine did not start"; exit 1
}
stop() { pkill -f radar-core 2>/dev/null || taskkill //F //IM radar-core.exe >/dev/null 2>&1; sleep 0.5; }
field() { python -c "import sys,json; d=json.load(sys.stdin); print($1)"; }
wait_done() {
  for _ in $(seq 1 240); do
    s=$(curl "${H[@]}" $URL/scans/$1)
    st=$(echo "$s" | field "d['state']")
    [ "$st" = done ] || [ "$st" = failed ] && { echo "$s" | field "d['state'], d['stage'], d['todo_count'], d['new_count'], d['resolved_count'], d.get('error')"; return; }
    sleep 0.5
  done
  echo "timeout"; echo "$s"
}

stop; rm -rf "$ROOT"; mkdir -p "$ROOT"; start
echo "== unauthorized without token:"; curl -s -o /dev/null -w "%{http_code}\n" $URL/scans
echo "== auth status:"; curl "${H[@]}" $URL/auth/status | field "d['state'], d['login'], d['source']"
echo "== my repos (first 3):"; curl "${H[@]}" "$URL/repos" | field "[(r['full_name'], r['size_kb'], r['too_large']) for r in d[:3]]"
echo "== search:"; curl "${H[@]}" "$URL/repos?q=rivium" | field "[(r['full_name'], r['too_large']) for r in d[:3]]"

echo "== scan 1 of $REPO_OWNER/$REPO_NAME:"
id1=$(curl "${H[@]}" -X POST $URL/scans -H 'content-type: application/json' -d "{\"owner\":\"$REPO_OWNER\",\"repo\":\"$REPO_NAME\"}" | field "d['id']")
wait_done $id1
echo "== todos (first 3):"; curl "${H[@]}" "$URL/scans/$id1/todos" | field "[(t['path'], t['line'], t['tag'], t['text'][:40], t['web_url']) for t in d[:3]]"
echo "== report head:"; curl "${H[@]}" "$URL/scans/$id1/report" | head -8
echo "== cache:"; ls "$ROOT/cache"

echo "== scan 2 (same sha -> no changes path):"
id2=$(curl "${H[@]}" -X POST $URL/scans -H 'content-type: application/json' -d "{\"owner\":\"$REPO_OWNER\",\"repo\":\"$REPO_NAME\"}" | field "d['id']")
wait_done $id2

echo "== keep scan 1, wait for default cleanup (20s):"
curl "${H[@]}" -X POST $URL/scans/$id1/keep | field "d['keep_files']"
sleep 26
curl "${H[@]}" $URL/scans/$id1 | field "'scan1 keep', d['keep_files'], 'deleted', d['files_deleted']"
ls "$ROOT/cache"
echo "== delete now on scan 1:"
curl "${H[@]}" -X POST $URL/scans/$id1/cleanup >/dev/null; sleep 4
curl "${H[@]}" $URL/scans/$id1 | field "'scan1 keep', d['keep_files'], 'deleted', d['files_deleted']"
ls "$ROOT/cache"

echo "== bad input:"; curl "${H[@]}" -X POST $URL/scans -H 'content-type: application/json' -d '{"owner":"../x","repo":"y"}'; echo

echo "== crash recovery: start scan 3, kill mid-flight, restart"
id3=$(curl "${H[@]}" -X POST $URL/scans -H 'content-type: application/json' -d "{\"owner\":\"$REPO_OWNER\",\"repo\":\"$REPO_NAME\",\"zip_path\":null}" | field "d['id']")
sleep 1.2; curl "${H[@]}" $URL/scans/$id3 | field "'before kill', d['state'], d['stage'], d['bytes_done'], d['entries_done']"
stop; start
t0=$(date +%s); wait_done $id3; echo "recovered in $(( $(date +%s) - t0 ))s"
stop

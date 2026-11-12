#!/usr/bin/env bash
# The drills Module 40 shows, on the lab stack (scripts/stack.sh up first): a point-in-time restore
# to a chosen moment; the same archive with one segment hidden, restored to its end (PostgreSQL opens
# a short copy and the checker refuses it); and restored to a target beyond the gap (PostgreSQL
# refuses to open). The stack tests check the same things; this prints them for a reader.
#
# Usage: scripts/demo-drills.sh [--run] [--yes]
#   default  a dry run: print the plan, change nothing
# The hidden segment is behind a delete marker in the locked bucket; the script removes the marker
# at the end, on every path, so the segment is visible again.
set -euo pipefail

readonly PROJECT="seatwave-recovery"
readonly STORE="https://127.0.0.1:24000/seatwave-backups"
readonly CONFIRMS=1200
readonly PER_SECOND=200
readonly SWITCH_EVERY_SECONDS=1.5

execute="no"
confirmed="no"
for option in "$@"; do
  case "$option" in
    --run) execute="yes" ;;
    --yes) confirmed="yes" ;;
    *) echo "usage: $0 [--run] [--yes]" >&2; exit 2 ;;
  esac
done
cd "$(dirname "$0")/.."
compose=(docker compose -p "$PROJECT")
echo "plan: on $PROJECT, a full backup, $CONFIRMS confirms at $PER_SECOND a second with a segment switch every"
echo "  $SWITCH_EVERY_SECONDS s, then three drills: to the moment before confirm 601; to the end with one"
echo "  segment hidden; to the last confirm with the segment hidden. The primary is not changed."
if [[ "$execute" != "yes" ]]; then
  echo "dry run: nothing changed. Add --run to do it."
  exit 0
fi
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Go ahead on $PROJECT? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing changed" >&2; exit 1; }
fi

mkdir -p build/demo
./gradlew -q stackClasspath
classpath="$(cat build/stack/classpath.txt)"
s3() {
  local method="$1" path="$2"
  shift 2
  curl -s -o build/demo/s3.xml -w '%{http_code}' -K .secrets/curl.cfg --aws-sigv4 "aws:amz:us-east-1:s3" \
    --cacert .secrets/tls/ca.pem -X "$method" "$@" "$STORE$path"
}
unhide() {
  s3 GET "?versions&prefix=seatwave/archive/" > /dev/null || return 0
  python3 -I -c '
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
for key, version in re.findall(r"<DeleteMarker>.*?<Key>([^<]+)</Key>.*?<VersionId>([^<]+)</VersionId>", text):
    print(key + "?versionId=" + version)
' build/demo/s3.xml | while read -r marker; do s3 DELETE "/$marker" > /dev/null; done
}
trap unhide EXIT

echo "\$ pgbackrest --stanza=seatwave --type=full backup"
"${compose[@]}" exec -T --user postgres primary pgbackrest --stanza=seatwave --type=full backup 2>&1 \
  | grep -E "new backup label|completed successfully" | grep -v expire | sed 's/^.*INFO: //'
echo "\$ ConfirmLoadCli $CONFIRMS $PER_SECOND build/demo/manifest.txt   (a segment switch every $SWITCH_EVERY_SECONDS s meanwhile)"
( while true; do
    "${compose[@]}" exec -T --user postgres primary psql -d seatwave -Atqc "SELECT pg_switch_wal()" > /dev/null
    sleep "$SWITCH_EVERY_SECONDS"
  done ) &
switcher=$!
java -cp "$classpath" io.plainround.seatwave.recovery.stack.ConfirmLoadCli config/stack.properties \
  "$CONFIRMS" "$PER_SECOND" build/demo/manifest.txt
kill "$switcher"
wait "$switcher" 2> /dev/null || true
"${compose[@]}" exec -T --user postgres primary pgbackrest --stanza=seatwave check > /dev/null 2>&1

target="$(grep '^ACKNOWLEDGED' build/demo/manifest.txt | sed -n '601p' | cut -f3)"
last="$(grep '^ACKNOWLEDGED' build/demo/manifest.txt | tail -1 | cut -f3)"
echo
echo "\$ scripts/restore-drill.sh build/demo/manifest.txt --before $target --run --yes   (the commit time of confirm 601)"
scripts/restore-drill.sh build/demo/manifest.txt --before "$target" --run --yes 2>&1 | sed -n '/^drill of/,$p'

s3 GET "?list-type=2&prefix=seatwave/archive/seatwave/" > /dev/null
hidden="$(python3 -I -c '
import re, sys
keys = sorted(key for key in re.findall(r"<Key>([^<]+)</Key>", open(sys.argv[1], encoding="utf-8").read())
              if re.search(r"/[0-9A-F]{24}-[0-9a-f]{40}\.zst$", key))
print(keys[-3])
' build/demo/s3.xml)"
echo
echo "\$ hide archived segment $(basename "$hidden" | cut -c1-24) (an S3 DELETE: in the locked bucket, a delete marker): HTTP $(s3 DELETE "/$hidden")"
echo "\$ scripts/restore-drill.sh build/demo/manifest.txt --end --run --yes"
scripts/restore-drill.sh build/demo/manifest.txt --end --run --yes 2>&1 | sed -n '/^drill of/,$p' || true
echo
echo "\$ scripts/restore-drill.sh build/demo/manifest.txt --before $last --run --yes   (beyond the hidden segment)"
scripts/restore-drill.sh build/demo/manifest.txt --before "$last" --run --yes 2>&1 | sed -n '/^drill of/,$p' || true
"${compose[@]}" --profile drill rm -sfv restore-target > /dev/null 2>&1

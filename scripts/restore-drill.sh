#!/usr/bin/env bash
# Timed restore drill for the seatwave-recovery lab (Module 40). Restores a backup from the
# repository into an empty restore-target, replays the archive to the moment given (or to its end),
# checks the copy with the JDBC checker against the drill manifest of the load generator, and logs
# when each step started and how long it took. It never touches the primary. The restored copy
# never serves traffic: a real recovery adds the erasure-ledger replay and the row-level security
# check of dr/region-b-recovery.tsv (Module 42) before it does.
#
# Usage: scripts/restore-drill.sh <manifest> --before <ISO-8601 UTC time> | --end [--set <label>] [--run] [--yes]
#   --before T  point-in-time recovery: stop just before T (exclusive, pgBackRest's
#               --target-exclusive; its default is inclusive), then promote
#   --end       replay everything the archive holds, then promote (a recovery after the primary is
#               lost); the checker then expects every acknowledged confirm
#   --set L     restore backup L instead of the newest
#   default     a dry run: print the plan, change nothing; --run does it, --yes skips the question
# Exit status 0 if the checker declared the copy good within the RTO, 1 if it refused the copy or a
# step failed, 2 on bad arguments.
set -euo pipefail

readonly PROJECT="seatwave-recovery"
readonly RTO_SECONDS=3600   # bookings' objective (Section 7); the drill fails above it
readonly PROMOTE_TIMEOUT_SECONDS=1800
readonly POLL_SECONDS=0.2

usage() {
  echo "usage: $0 <manifest> --before <ISO-8601 UTC time> | --end [--set <label>] [--run] [--yes]" >&2
  exit 2
}

[[ $# -ge 2 ]] || usage
readonly MANIFEST="$1"
shift
target=""
mode=""
backup_set=""
execute="no"
confirmed="no"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --before)
      [[ $# -ge 2 && "$2" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+Z$ ]] || { echo "--before takes a time like 2026-11-12T19:14:20.225407Z" >&2; exit 2; }
      mode="before"; target="$2"; shift ;;
    --end) mode="end" ;;
    --set)
      [[ $# -ge 2 && "$2" =~ ^[0-9]{8}-[0-9]{6}F(_[0-9]{8}-[0-9]{6}[DI])?$ ]] || { echo "--set takes a pgBackRest label like 20261112-191313F" >&2; exit 2; }
      backup_set="$2"; shift ;;
    --run) execute="yes" ;;
    --yes) confirmed="yes" ;;
    *) usage ;;
  esac
  shift
done
[[ -n "$mode" && -s "$MANIFEST" ]] || usage

cd "$(dirname "$0")/.."
compose=(docker compose -p "$PROJECT" --profile drill)
restore_options=(--stanza=seatwave)
[[ -n "$backup_set" ]] && restore_options+=("--set=$backup_set")
if [[ "$mode" == "before" ]]; then
  # pgBackRest takes the target in PostgreSQL's time format.
  pg_target="$(printf '%s' "$target" | sed -e 's/T/ /' -e 's/Z$/+00/')"
  restore_options+=(--type=time "--target=$pg_target" --target-exclusive --target-action=promote)
  check_options=(--before "$target")
else
  restore_options+=(--type=default)
  check_options=(--everything)
fi

echo "plan: restore drill on $PROJECT into restore-target (the primary is not touched)"
echo "  create target: remove restore-target and its volume, create it empty"
echo "  restore:       pgbackrest ${restore_options[*]} restore"
echo "  replay:        start restore-target; wait until it has promoted"
echo "  verify:        RestoreCheckCli $(basename "$MANIFEST") ${check_options[*]} restore"
if [[ "$execute" != "yes" ]]; then
  echo "dry run: nothing changed. Add --run to do it."
  exit 0
fi
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Go ahead with the drill on $PROJECT? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing changed" >&2; exit 1; }
fi

mkdir -p build/drill
drill_log="build/drill/drill-$(date -u +%Y%m%dT%H%M%SZ).log"
readonly drill_log
# The checker runs from the compiled classes, so Gradle's start-up is not part of the verify step.
./gradlew -q stackClasspath
classpath="$(cat build/stack/classpath.txt)"

now() {
  python3 -I -c 'import time; print(f"{time.time():.3f}")'
}
seconds_between() {
  python3 -I -c 'import sys; print(f"{float(sys.argv[2]) - float(sys.argv[1]):.3f}")' "$1" "$2"
}
clock_of() {
  python3 -I -c 'import sys, datetime; print(datetime.datetime.fromtimestamp(float(sys.argv[1]), datetime.timezone.utc).strftime("%H:%M:%S.%f")[:12])' "$1"
}

# step <name> <command...>: runs one step and logs when it started and how long it took.
step() {
  local name="$1" started finished
  shift
  started="$(now)"
  if ! "$@"; then
    printf '%s\t%s\t%ss\tFAILED\n' "$name" "$(clock_of "$started")" "$(seconds_between "$started" "$(now)")" >> "$drill_log"
    cat "$drill_log"
    exit 1
  fi
  finished="$(now)"
  printf '%s\t%s\t%ss\n' "$name" "$(clock_of "$started")" "$(seconds_between "$started" "$finished")" >> "$drill_log"
}

create_target() {
  "${compose[@]}" rm -sfv restore-target > /dev/null 2>&1 || true
  if docker volume inspect "${PROJECT}_restore-data" > /dev/null 2>&1; then
    docker volume rm "${PROJECT}_restore-data" > /dev/null
  fi
  "${compose[@]}" create restore-target > /dev/null 2>&1
}

restore() {
  "${compose[@]}" run --rm --no-deps -T --user postgres --entrypoint pgbackrest restore-target \
    "${restore_options[@]}" restore > build/drill/restore.txt 2>&1 || { tail -5 build/drill/restore.txt >&2; return 1; }
  grep -E "restore backup set|restore size|restore command end" build/drill/restore.txt | sed 's/^.*INFO: /  /' >> "$drill_log"
}

replay() {
  "${compose[@]}" start restore-target > /dev/null
  local started container
  started="$(now)"
  container="$("${compose[@]}" ps -aq restore-target)"
  while python3 -I -c "import sys,time; sys.exit(0 if time.time() - $started < $PROMOTE_TIMEOUT_SECONDS else 1)"; do
    # PostgreSQL shuts down with a fatal error when the archive ends before a set target.
    if [[ "$(docker inspect -f '{{.State.Running}}' "$container")" != "true" ]]; then
      echo "REFUSED: the restored server stopped during replay:" | tee -a "$drill_log" >&2
      "${compose[@]}" logs --no-log-prefix restore-target 2>&1 | grep -E "FATAL|recovery ended|redo|stopping" | tail -4 | sed 's/^/  /' | tee -a "$drill_log" >&2
      return 1
    fi
    if [[ "$("${compose[@]}" exec -T --user postgres restore-target psql -d seatwave -Atc 'SELECT pg_is_in_recovery()' 2> /dev/null || true)" == "f" ]]; then
      "${compose[@]}" logs --no-log-prefix restore-target 2>&1 | grep -E "redo done|recovery stopping|last completed transaction|selected new timeline" | sed 's/^/  /' >> "$drill_log"
      return 0
    fi
    sleep "$POLL_SECONDS"
  done
  echo "REFUSED: the restored server did not promote within $PROMOTE_TIMEOUT_SECONDS s" >&2
  return 1
}

verify() {
  java -cp "$classpath" io.plainround.seatwave.recovery.stack.RestoreCheckCli \
    config/stack.properties "$MANIFEST" "${check_options[@]}" restore | sed 's/^/  /' | tee -a "$drill_log"
  return "${PIPESTATUS[0]}"
}

drill_start="$(now)"
printf 'drill of %s: %s, manifest %s\n' "$PROJECT" "${check_options[*]}" "$(basename "$MANIFEST")" > "$drill_log"
step "create target" create_target
step "restore" restore
step "replay" replay
step "verify" verify
took="$(seconds_between "$drill_start" "$(now)")"
verdict="$(python3 -I -c 'import sys; print("PASS" if float(sys.argv[1]) <= float(sys.argv[2]) else "FAIL")' "$took" "$RTO_SECONDS")"
printf 'total\t%ss\tRTO %ss\t%s\n' "$took" "$RTO_SECONDS" "$verdict" >> "$drill_log"
cat "$drill_log"
[[ "$verdict" == "PASS" ]]

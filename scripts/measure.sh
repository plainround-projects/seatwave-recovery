#!/usr/bin/env bash
# The measured runs Module 40 quotes, on the lab stack (scripts/stack.sh up first), in three phases,
# each under the course's measurement lock (held for one phase at a time, released on every path).
# Writes one file per measurement into measurements/ (or SEATWAVE_MEASURE_OUT), never under build/.
#
# Usage: scripts/measure.sh <drill|lag|kill> [--rows N] [--run] [--yes]
#   drill  load N bookings (default 5,000,000) by SQL; a full backup with 4 processes and one with
#          1, each timed; verify, timed; about 1 GB more log by SQL and 2,000 confirms; the timed
#          drill to the end of the archive (restore, replay, verify); a restore with 1 process
#   lag    the archive's lag under 200 confirms a second; the pilot light: subscribe the region-b
#          copy (initial copy timed), its lag idle and under load, the promotion and a confirm
#   kill   Lab 3: kill the primary under load and start it again (crash recovery); then kill it
#          for good and restore from the archive (what a lost primary's disk costs)
#   default a dry run: print the plan, change nothing
# SEATWAVE_MEASURE_LOCK names the lock folder that the stacks sharing this machine agree on.
set -euo pipefail

readonly PROJECT="seatwave-recovery"
# The lock folder every course stack on this machine shares, named by the environment.
readonly LOCK="${SEATWAVE_MEASURE_LOCK:?set SEATWAVE_MEASURE_LOCK to the shared measurement lock folder}"
readonly LOCK_STALE_MINUTES=40
readonly LOCK_POLL_SECONDS=30

usage() {
  echo "usage: $0 <drill|lag|kill> [--rows N] [--run] [--yes]" >&2
  exit 2
}
[[ $# -ge 1 ]] || usage
phase="$1"
shift
case "$phase" in drill|lag|kill) ;; *) usage ;; esac
rows=5000000
execute="no"
confirmed="no"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --rows) [[ $# -ge 2 && "$2" =~ ^[1-9][0-9]{0,7}$ ]] || usage; rows=$((10#$2)); shift ;;
    --run) execute="yes" ;;
    --yes) confirmed="yes" ;;
    *) usage ;;
  esac
  shift
done

cd "$(dirname "$0")/.."
compose=(docker compose -p "$PROJECT" --profile drill --profile pilot-light)
# Not under build/: "./gradlew clean" must not delete measured results.
out="${SEATWAVE_MEASURE_OUT:-measurements}"
echo "plan: measurement phase '$phase' on $PROJECT under the lock; results in $out/"
if [[ "$execute" != "yes" ]]; then
  echo "dry run: nothing changed. Add --run to do it."
  exit 0
fi
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Go ahead with phase '$phase' on $PROJECT? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing changed" >&2; exit 1; }
fi
mkdir -p "$out"
./gradlew -q stackClasspath
classpath="$(cat build/stack/classpath.txt)"

# The lock: mkdir fails while another agent holds it; a lock older than 40 minutes is stale.
until mkdir "$LOCK" 2> /dev/null; do
  if [[ -n "$(find "$LOCK/owner" -mmin +"$LOCK_STALE_MINUTES" 2> /dev/null)" ]]; then
    rm -r "$LOCK"
    continue
  fi
  echo "measurement lock held by $(cat "$LOCK/owner" 2> /dev/null || echo unknown); waiting" >&2
  sleep "$LOCK_POLL_SECONDS"
done
trap 'rm -r "$LOCK"' EXIT
echo "$PROJECT $(date -u +%Y-%m-%dT%H:%M:%SZ) phase $phase" > "$LOCK/owner"

note() {
  printf '%s  [load average %s]\n' "$1" "$(sysctl -n vm.loadavg | tr -d '{}' | awk '{print $1", "$2", "$3}')"
}
psql_primary() {
  "${compose[@]}" exec -T --user postgres primary psql -v ON_ERROR_STOP=1 -d seatwave -At "$@"
}
pgbackrest_primary() {
  "${compose[@]}" exec -T --user postgres primary pgbackrest --stanza=seatwave "$@"
}
java_run() {
  java -cp "$classpath" "io.plainround.seatwave.recovery.stack.$1" config/stack.properties "${@:2}"
}

drill_phase() {
  {
    note "phase drill started"
    psql_primary -c "SELECT version()" | cut -c1-40
    echo "loading $rows bookings and their customers by SQL (one statement each)"
    psql_primary <<SQL
INSERT INTO booking.customers (customer_ref, created_at)
  SELECT 'scale-' || number, now() FROM generate_series(1, $rows) AS number;
INSERT INTO booking.bookings (booking_id, event_id, seat_label, customer_id, price_paid, currency, confirmed_at)
  SELECT gen_random_uuid(), 1000 + customer_id / 20000, 'S-' || customer_id, customer_id, 95.00, 'EUR', now()
  FROM booking.customers WHERE customer_ref LIKE 'scale-%';
CHECKPOINT;
SQL
    psql_primary -c "SELECT 'database size: ' || pg_database_size('seatwave') || ' bytes ('
      || pg_size_pretty(pg_database_size('seatwave')) || ')'"
    note "full backup, process-max 1"
    pgbackrest_primary --type=full --process-max=1 backup > "$out/backup-1.txt" 2>&1
    grep -E "full backup size|completed successfully" "$out/backup-1.txt" | grep -v expire | sed 's/^.*INFO: /  /'
    note "full backup, process-max 4"
    pgbackrest_primary --type=full --process-max=4 backup > "$out/backup-4.txt" 2>&1
    grep -E "full backup size|completed successfully" "$out/backup-4.txt" | grep -v expire | sed 's/^.*INFO: /  /'
    pgbackrest_primary info --output=json --log-level-console=off > "$out/info.json"
    note "verify, process-max 4"
    pgbackrest_primary verify --output=text --verbose > "$out/verify.txt" 2>&1
    grep -E "^status|backup:|verify command end" "$out/verify.txt" | sed 's/^.*INFO: /  /'
    local before after
    before="$(psql_primary -c "SELECT pg_current_wal_lsn()")"
    echo "writing about 1 GB of log by SQL after the backup"
    psql_primary <<SQL
INSERT INTO booking.customers (customer_ref, created_at)
  SELECT 'replay-' || number, now() FROM generate_series(1, $((rows / 2))) AS number;
INSERT INTO booking.outbox (booking_id, payload, created_at)
  SELECT gen_random_uuid(), repeat('x', 200), now() FROM generate_series(1, $((rows / 2)));
SQL
    after="$(psql_primary -c "SELECT pg_current_wal_lsn()")"
    psql_primary -c "SELECT 'log written after the backup: ' || pg_wal_lsn_diff('$after', '$before') || ' bytes'"
    java_run ConfirmLoadCli 2000 200 "$out/drill-manifest.txt"
    note "check: switch and wait for the archive"
    pgbackrest_primary check > "$out/check.txt" 2>&1
    grep -E "successfully archived" "$out/check.txt" | sed 's/^.*INFO: /  /'
    note "the drill: newest backup, replay to the end of the archive, the checker"
    scripts/restore-drill.sh "$out/drill-manifest.txt" --end --run --yes || true
    "${compose[@]}" exec -T --user postgres restore-target psql -d seatwave -At \
      -c "SELECT 'replayed log: ' || pg_wal_lsn_diff(pg_current_wal_lsn(), '$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))[0]["backup"][-1]["lsn"]["start"])' "$out/info.json")') || ' bytes from the backup start to the end of replay'"
    note "restore only, process-max 1, into a fresh target"
    "${compose[@]}" rm -sfv restore-target > /dev/null 2>&1
    docker volume rm "${PROJECT}_restore-data" > /dev/null
    "${compose[@]}" create restore-target > /dev/null 2>&1
    "${compose[@]}" run --rm --no-deps -T --user postgres --entrypoint pgbackrest restore-target \
      --stanza=seatwave --process-max=1 --type=default restore 2>&1 | grep -E "restore size|completed successfully" | sed 's/^.*INFO: /  /'
    "${compose[@]}" rm -sfv restore-target > /dev/null 2>&1
    note "phase drill ended"
  } 2>&1 | tee "$out/drill.txt"
}

lag_phase() {
  {
    note "phase lag started"
    java_run ConfirmLoadCli 24000 200 "$out/lag-manifest.txt" > "$out/lag-load.txt" 2> "$out/lag-load-warnings.txt" &
    local loader=$!
    java_run ArchiveLagCli 110 10
    wait "$loader"
    cat "$out/lag-load.txt"
    note "pilot light: start the region-b copy and subscribe it"
    "${compose[@]}" up -d --wait region-b-copy > /dev/null 2>&1
    java_run PilotLightCli subscribe
    note "lag, idle"
    java_run PilotLightCli lag 200
    note "lag, under 200 confirms a second"
    java_run ConfirmLoadCli 8000 200 "$out/pilot-manifest.txt" > "$out/pilot-load.txt" 2> "$out/pilot-load-warnings.txt" &
    loader=$!
    sleep 2
    java_run PilotLightCli lag 150
    wait "$loader"
    cat "$out/pilot-load.txt"
    sleep 2
    java -cp "$classpath" io.plainround.seatwave.recovery.stack.RestoreCheckCli config/stack.properties \
      "$out/pilot-manifest.txt" --everything copy || true
    note "promotion"
    java_run PilotLightCli promote
    java_run PilotLightCli confirm
    "${compose[@]}" rm -sfv region-b-copy > /dev/null 2>&1
    docker volume rm "${PROJECT}_region-b-data" > /dev/null
    psql_primary -c "SELECT count(pg_drop_replication_slot(slot_name)) || ' slot dropped' FROM pg_replication_slots WHERE slot_name = 'seatwave_bookings' AND NOT active"
    note "phase lag ended"
  } 2>&1 | tee "$out/lag.txt"
}

kill_phase() {
  {
    note "phase kill started: Lab 3, the primary killed under 200 confirms a second and started again"
    java_run ConfirmLoadCli 6000 200 "$out/kill-manifest.txt" > "$out/kill-load.txt" 2> "$out/kill-load-warnings.txt" &
    local loader=$!
    sleep 10
    local killed ready
    killed="$(python3 -I -c 'import time; print(f"{time.time():.3f}")')"
    "${compose[@]}" kill primary > /dev/null 2>&1
    "${compose[@]}" start primary > /dev/null 2>&1
    until "${compose[@]}" exec -T primary pg_isready -q -U postgres -d seatwave 2> /dev/null; do sleep 0.1; done
    ready="$(python3 -I -c 'import time; print(f"{time.time():.3f}")')"
    python3 -I -c 'import sys; print(f"kill to ready to accept connections: {float(sys.argv[2]) - float(sys.argv[1]):.3f} s")' "$killed" "$ready"
    "${compose[@]}" logs --no-log-prefix primary 2>&1 | grep -E "not properly shut down|redo starts|redo done|ready to accept" | tail -4
    wait "$loader"
    cat "$out/kill-load.txt"
    java -cp "$classpath" io.plainround.seatwave.recovery.stack.RestoreCheckCli config/stack.properties \
      "$out/kill-manifest.txt" --everything primary || true
    note "the primary's disk lost: a fresh backup, then 200 confirms a second, the primary killed for good"
    pgbackrest_primary --type=full backup 2>&1 | grep -E "completed successfully" | grep -v expire | sed 's/^.*INFO: /  /'
    java_run ConfirmLoadCli 18000 200 "$out/lost-manifest.txt" > "$out/lost-load.txt" 2> "$out/lost-load-warnings.txt" &
    loader=$!
    sleep 75
    date -u +"primary killed at %Y-%m-%dT%H:%M:%SZ"
    "${compose[@]}" kill primary > /dev/null 2>&1
    wait "$loader" || true
    cat "$out/lost-load.txt"
    echo "last acknowledged confirm committed at $(grep '^ACKNOWLEDGED' "$out/lost-manifest.txt" | tail -1 | cut -f3)"
    scripts/restore-drill.sh "$out/lost-manifest.txt" --end --run --yes || true
    note "phase kill ended"
  } 2>&1 | tee "$out/kill.txt"
}

case "$phase" in
  drill) drill_phase ;;
  lag) lag_phase ;;
  kill) kill_phase ;;
esac

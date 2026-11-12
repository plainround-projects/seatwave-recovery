#!/usr/bin/env bash
# Starts, tests and stops Module 40's stack (RustFS 1.0.5 with TLS, PostgreSQL 18.6 with pgBackRest
# 2.60.0 archiving to it) under the Compose project name seatwave-recovery, ports on 127.0.0.1 only.
#
# Usage: scripts/stack.sh <up|down|test|status> [--run] [--yes]
#   default   a dry run: print the plan, change nothing (and need no Docker)
#   --run     do it; asks for confirmation on the terminal first
#   --yes     skip the question
#
#   up      generate the store's keys, the database passwords, the cipher passphrase and a lab
#           certificate authority with the store's certificate into .secrets; build the PostgreSQL
#           image; start the store and the primary and wait until both answer; create the bucket;
#           create the stanza and check that a log segment reaches the repository
#   down    remove the project's containers, network and volumes (down -v) and the credentials of
#           the stack that is now gone, then check that nothing of the project is left
#   test    up, ./gradlew stackTest, then down, on every path
#   status  list the project's containers (read-only)
set -euo pipefail

readonly PROJECT="seatwave-recovery"
readonly WAIT_SECONDS=120
readonly S3_ENDPOINT="https://127.0.0.1:24000"
readonly BUCKET="seatwave-backups"
readonly DOCKER_TIMEOUT_SECONDS=10
# An S3 access key id of 20 characters and a secret key of 40, the lengths AWS uses.
readonly ACCESS_KEY_BYTES=10
readonly SECRET_KEY_BYTES=20
readonly PASSWORD_BYTES=24
readonly CIPHER_PASS_BYTES=32
readonly CERTIFICATE_DAYS=2
# The lab bucket's Object Lock retention; down -v removes the volume, locked versions included.
readonly LOCK_DAYS=1
readonly PASSWORD_FILES=(postgres.password owner.password app.password replicator.password)

usage() {
  echo "usage: $0 <up|down|test|status> [--run] [--yes]" >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
action="$1"
shift
mode="dry-run"
confirmed="no"
for option in "$@"; do
  case "$option" in
    --run) mode="run" ;;
    --yes) confirmed="yes" ;;
    *) usage ;;
  esac
done
case "$action" in up|down|test|status) ;; *) usage ;; esac

cd "$(dirname "$0")/.."
compose=(docker compose -p "$PROJECT")

echo "plan: $action the Compose project $PROJECT"
case "$action" in
  up) echo "  write .secrets (keys, passwords, passphrase, lab certificates) if missing; build the image;"
      echo "  ${compose[*]} up -d --wait object-store primary; create bucket $BUCKET; stanza-create; check" ;;
  down) echo "  ${compose[*]} --profile drill --profile pilot-light down -v --remove-orphans; remove .secrets; check nothing is left" ;;
  test) echo "  up, then ./gradlew stackTest, then down -v (always)" ;;
  status) echo "  ${compose[*]} ps --all" ;;
esac
# The dry run comes before the Docker check: it changes nothing, so it needs nothing.
if [[ "$mode" == "dry-run" && "$action" != "status" ]]; then
  echo "dry run: nothing changed. Add --run to do it."
  exit 0
fi

# macOS has no timeout command, so the check runs in the background and is killed if it hangs.
# "docker info --format" exits 0 even when the engine does not answer (Docker 24.0.6), so the
# check also needs the version it printed.
docker_answers() {
  local answer_file="build/stack/docker-version"
  mkdir -p build/stack
  rm -f "$answer_file"
  docker info --format '{{.ServerVersion}}' > "$answer_file" 2> /dev/null &
  local checker=$!
  local waited
  for (( waited = 0; waited < DOCKER_TIMEOUT_SECONDS; waited++ )); do
    if ! kill -0 "$checker" 2> /dev/null; then
      wait "$checker" || return 1
      grep -q "[0-9]" "$answer_file"
      return $?
    fi
    sleep 1
  done
  kill "$checker" 2> /dev/null || true
  return 1
}
if ! command -v docker > /dev/null || ! docker_answers; then
  echo "Docker is not running (or does not answer within ${DOCKER_TIMEOUT_SECONDS} s); nothing was changed." >&2
  exit 1
fi
if [[ "$action" == "status" ]]; then
  "${compose[@]}" ps --all
  exit 0
fi
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Go ahead with '$action' for $PROJECT? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing changed" >&2; exit 1; }
fi

mkdir -p build/stack
audit() {
  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) $1" >> build/stack/audit.log
}

# Readable by all, not owner-only: Compose secrets and bind mounts keep the file's owner and mode,
# and the store (uid 10001) and PostgreSQL (uid 999) run as their own users inside the containers.
# Any account on this machine can read these throwaway lab credentials; down deletes them.
write_random() {
  local file="$1" bytes="$2"
  if [[ ! -s ".secrets/$file" ]]; then
    ( umask 022; openssl rand -hex "$bytes" > ".secrets/$file" )
  fi
}

certificates() {
  # A lab authority valid for two days and the store's certificate for the names the clients use:
  # object-store inside the Compose network, 127.0.0.1 from this machine.
  [[ -s .secrets/tls/store/rustfs_cert.pem ]] && return 0
  mkdir -p .secrets/tls/store
  ( umask 022
    openssl req -x509 -newkey rsa:2048 -nodes -days "$CERTIFICATE_DAYS" -subj "/CN=seatwave-recovery lab authority" \
      -keyout .secrets/tls/ca.key -out .secrets/tls/ca.pem 2> /dev/null
    openssl req -newkey rsa:2048 -nodes -subj "/CN=object-store" \
      -keyout .secrets/tls/store/rustfs_key.pem -out .secrets/tls/store.csr 2> /dev/null
    printf 'subjectAltName=DNS:object-store,DNS:localhost,IP:127.0.0.1\n' > .secrets/tls/store.ext
    openssl x509 -req -in .secrets/tls/store.csr -CA .secrets/tls/ca.pem -CAkey .secrets/tls/ca.key \
      -CAcreateserial -days "$CERTIFICATE_DAYS" -extfile .secrets/tls/store.ext \
      -out .secrets/tls/store/rustfs_cert.pem 2> /dev/null )
  # The authority's key signed one certificate and is not needed again.
  rm -f .secrets/tls/ca.key .secrets/tls/store.csr .secrets/tls/store.ext .secrets/tls/ca.srl
}

secrets() {
  # Not under build/: "./gradlew clean" would delete the credentials while the stack uses them.
  mkdir -p .secrets/pgbackrest
  write_random store-access-key "$ACCESS_KEY_BYTES"
  write_random store-secret-key "$SECRET_KEY_BYTES"
  local file
  for file in "${PASSWORD_FILES[@]}"; do
    write_random "$file" "$PASSWORD_BYTES"
  done
  if [[ ! -s .secrets/pgbackrest/credentials.conf ]]; then
    ( umask 022
      printf '[global]\nrepo1-s3-key=%s\nrepo1-s3-key-secret=%s\nrepo1-cipher-pass=%s\n' \
        "$(< .secrets/store-access-key)" "$(< .secrets/store-secret-key)" \
        "$(openssl rand -hex "$CIPHER_PASS_BYTES")" > .secrets/pgbackrest/credentials.conf )
  fi
  # curl reads the store's key pair from this file, so it never appears on a command line.
  ( umask 077; printf 'user = "%s:%s"\n' "$(< .secrets/store-access-key)" "$(< .secrets/store-secret-key)" > .secrets/curl.cfg )
  certificates
}

# One signed S3 request to the store (Signature Version 4, region us-east-1); prints the status.
s3() {
  local method="$1" path="$2"
  shift 2
  curl -s -o build/stack/s3-response.xml -w '%{http_code}' -K .secrets/curl.cfg \
    --aws-sigv4 "aws:amz:us-east-1:s3" --cacert .secrets/tls/ca.pem -X "$method" "$@" "$S3_ENDPOINT/$path"
}

as_postgres() {
  "${compose[@]}" exec -T --user postgres primary "$@"
}

up() {
  secrets
  "${compose[@]}" build --quiet primary || return 1
  # --wait returns when every healthcheck passes, or fails after the timeout.
  # Called in "up || status=$?", where set -e is off, so each failure returns on its own.
  "${compose[@]}" up -d --wait --wait-timeout "$WAIT_SECONDS" --quiet-pull object-store primary || return 1
  audit "up: started $PROJECT"
  # The health endpoint answers before the S3 API is ready; an anonymous request is refused with
  # 403 once it is, so 403 (over TLS, with the lab authority checked) is the sign of readiness.
  local waited answer=""
  for (( waited = 0; waited < WAIT_SECONDS; waited++ )); do
    answer="$(curl -s -o /dev/null -w '%{http_code}' --cacert .secrets/tls/ca.pem "$S3_ENDPOINT/$BUCKET" || true)"
    [[ "$answer" == "403" ]] && break
    sleep 1
  done
  [[ "$answer" == "403" ]] || { echo "the store still answered $answer after $WAIT_SECONDS s" >&2; return 1; }
  # The bucket is versioned with Object Lock: every version is kept under COMPLIANCE retention, so
  # no key, not even the store's root key, deletes a backup early (Section 10's rule; production
  # keeps 35 days, the lab 1 day). A delete only adds a delete marker; the version stays readable.
  answer="$(s3 PUT "$BUCKET" -H 'x-amz-bucket-object-lock-enabled: true')"
  [[ "$answer" == "200" ]] || { echo "creating bucket $BUCKET was answered $answer" >&2; cat build/stack/s3-response.xml >&2; return 1; }
  local lock_rule digest
  lock_rule="<ObjectLockConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><ObjectLockEnabled>Enabled</ObjectLockEnabled><Rule><DefaultRetention><Mode>COMPLIANCE</Mode><Days>$LOCK_DAYS</Days></DefaultRetention></Rule></ObjectLockConfiguration>"
  digest="$(printf '%s' "$lock_rule" | openssl dgst -md5 -binary | base64)"
  answer="$(s3 PUT "$BUCKET?object-lock" -H "Content-MD5: $digest" --data-binary "$lock_rule")"
  [[ "$answer" == "200" ]] || { echo "setting the bucket's Object Lock rule was answered $answer" >&2; cat build/stack/s3-response.xml >&2; return 1; }
  audit "up: bucket $BUCKET created with Object Lock (COMPLIANCE, $LOCK_DAYS day)"
  as_postgres pgbackrest --stanza=seatwave stanza-create || return 1
  # check forces a segment switch and waits until that segment is in the repository.
  as_postgres pgbackrest --stanza=seatwave check || return 1
  audit "up: stanza seatwave created and archiving checked"
}

down() {
  "${compose[@]}" --profile drill --profile pilot-light down -v --remove-orphans || return 1
  # The credentials belonged to the stack just removed; the next up makes new ones.
  rm -rf .secrets
  audit "down: removed $PROJECT and its credentials"
  # Each listing must succeed: an engine that does not answer lists nothing, which is not "clean".
  local left="" listed
  listed="$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT")" || return 1
  left+="$listed "
  listed="$(docker network ls -q --filter "label=com.docker.compose.project=$PROJECT")" || return 1
  left+="$listed "
  listed="$(docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT")" || return 1
  left+="$listed"
  if [[ -n "${left// /}" ]]; then
    echo "left behind by $PROJECT: $left" >&2
    return 1
  fi
  echo "nothing of $PROJECT is left"
}

case "$action" in
  up) up ;;
  down) down ;;
  test)
    status=0
    up || status=$?
    if [[ "$status" -eq 0 ]]; then
      ./gradlew stackTest || status=$?
    fi
    down || status=$?
    exit "$status"
    ;;
esac

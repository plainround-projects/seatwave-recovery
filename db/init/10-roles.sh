#!/usr/bin/env bash
# Runs once inside a postgres container, as the superuser, when its data volume is new (the
# primary and the region-b copy; a restored copy gets its roles from the backup).
#   seatwave_owner       LOGIN, owns the schema booking and its tables, applies the migration
#   seatwave_app         LOGIN: the confirms of the load generator and the checker's reads; owns
#                        nothing and cannot change the schema
#   seatwave_replicator  LOGIN REPLICATION: the region-b copy's subscription; may read the
#                        published tables and nothing else
# The superuser is used only for what needs it: pgBackRest's backup and restore, and creating,
# disabling and dropping the subscription on the copy. Passwords come from Compose secrets, as psql
# variables, never pasted into SQL text.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v owner_password="$(< /run/secrets/owner_password)" \
  -v app_password="$(< /run/secrets/app_password)" \
  -v replicator_password="$(< /run/secrets/replicator_password)" <<'SQL'
CREATE ROLE seatwave_owner LOGIN NOSUPERUSER PASSWORD :'owner_password';
CREATE ROLE seatwave_app LOGIN NOSUPERUSER PASSWORD :'app_password';
CREATE ROLE seatwave_replicator LOGIN NOSUPERUSER REPLICATION PASSWORD :'replicator_password';
GRANT CONNECT, CREATE ON DATABASE seatwave TO seatwave_owner;
GRANT CONNECT ON DATABASE seatwave TO seatwave_app, seatwave_replicator;
REVOKE CONNECT ON DATABASE seatwave FROM PUBLIC;
SQL
# Through the local socket of the start-up server, which listens on no TCP port during this step.
psql -v ON_ERROR_STOP=1 --username seatwave_owner --dbname "$POSTGRES_DB" \
  -f /seatwave/V40_1__bookings.sql

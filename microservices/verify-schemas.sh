#!/usr/bin/env bash
# =============================================================================
# MyPlus - is every database migrated to the version this checkout expects?
#
#   ./verify-schemas.sh                     # every service that owns a schema
#   ./verify-schemas.sh business catalog    # or just some
#
# Exit 0 = every schema matches the repo. Exit 1 = at least one does not.
# Read-only: safe to run against production at any time.
#
# WHY THIS EXISTS
# ---------------
# A container starts perfectly happily on a STALE JAR. Nothing errors, the healthcheck passes, and the app
# then misbehaves in ways that read like application bugs. The reliable tell is that the schema is behind
# the migrations in the checkout you believe you deployed.
#
# The runbooks used to carry a hand-written list of expected versions - "business 36, catalog 8, ...".
# That list went stale four separate times, and a stale expectation is worse than none: it either passes a
# genuinely stale deployment or sends you rebuilding jars that were fine. So this derives the expected
# version from the migration files ON DISK. It cannot drift from the repo, because it IS the repo - the
# same reasoning that moved the deployable service list into a compose profile.
#
# NOT `MAX(version)`. flyway_schema_history.version is a VARCHAR, so MAX() compares lexically and a schema
# at V40 reports '9' (the string '9' sorts above '40'), making a fully-migrated database look years out of
# date. ORDER BY installed_rank - an integer, and the true apply order.
#
# Two docker round trips total, not two per service: on Docker Desktop each `docker exec` costs seconds,
# and a 32-call version of this took over two minutes, which is long enough that nobody runs it.
# =============================================================================
set -uo pipefail

cd "$(dirname "$0")"

CONTAINER="${MYSQL_CONTAINER:-myplus-mysql}"

[ -f .env ] || { echo "FATAL: microservices/.env not found" >&2; exit 1; }
DB_PASSWORD="$(grep -m1 '^DB_PASSWORD=' .env | cut -d= -f2-)"
[ -n "$DB_PASSWORD" ] || { echo "FATAL: DB_PASSWORD is empty in .env" >&2; exit 1; }

docker inspect "$CONTAINER" >/dev/null 2>&1 || {
  echo "FATAL: container '$CONTAINER' not found - is the stack up?" >&2; exit 1; }

mysql_q() { docker exec -e MYSQL_PWD="$DB_PASSWORD" "$CONTAINER" mysql -uroot -N -e "$1" 2>/dev/null; }

# service-directory name -> database. Every service is myplusdb_<base> except business-service, which owns
# the original myplusdb.
db_for() { [ "$1" = "business" ] && echo "myplusdb" || echo "myplusdb_$1"; }

# ---- gather what the REPO expects -------------------------------------------------------------------
services=(); dbs=(); repos=()
for dir in */src/main/resources/db/migration; do
  [ -d "$dir" ] || continue
  svc="${dir%%/*}"; base="${svc%-service}"

  if [ "$#" -gt 0 ]; then
    match=0; for want in "$@"; do [ "$base" = "${want%-service}" ] && match=1; done
    [ "$match" = 1 ] || continue
  fi

  repo="$(ls "$dir" 2>/dev/null | grep -oE '^V[0-9]+' | sed 's/V//' | sort -n | tail -1)"
  [ -n "$repo" ] || continue
  services+=("$svc"); dbs+=("$(db_for "$base")"); repos+=("$repo")
done

[ "${#services[@]}" -gt 0 ] || {
  echo "Nothing matched. Pass service names without the -service suffix, e.g. business catalog"; exit 1; }

# ---- round trip 1: which of those schemas actually exist? --------------------------------------------
# Querying a missing schema aborts the whole UNION below, so filter first rather than lose every result
# to one absent database.
existing="$(mysql_q "SELECT DISTINCT table_schema FROM information_schema.tables
                      WHERE table_name='flyway_schema_history';")"

# ---- round trip 2: latest applied version + failed-migration count, for all schemas at once ----------
sql=""
for i in "${!dbs[@]}"; do
  db="${dbs[$i]}"
  grep -qx "$db" <<<"$existing" || continue
  [ -n "$sql" ] && sql+=" UNION ALL "
  sql+="SELECT '$db',
          (SELECT version FROM \`$db\`.flyway_schema_history WHERE success=1
             ORDER BY installed_rank DESC LIMIT 1),
          (SELECT COUNT(*) FROM \`$db\`.flyway_schema_history WHERE success=0)"
done

declare -A live_v failed_n
if [ -n "$sql" ]; then
  while IFS=$'\t' read -r db v f; do
    [ -n "$db" ] && { live_v["$db"]="$v"; failed_n["$db"]="$f"; }
  done <<<"$(mysql_q "$sql;")"
fi

# ---- report ------------------------------------------------------------------------------------------
printf '%-22s %-24s %6s %6s  %s\n' SERVICE DATABASE REPO LIVE STATUS
printf '%.0s-' {1..80}; echo

fail=0
for i in "${!services[@]}"; do
  svc="${services[$i]}"; db="${dbs[$i]}"; repo="${repos[$i]}"
  live="${live_v[$db]:-}"; nbad="${failed_n[$db]:-0}"

  if ! grep -qx "$db" <<<"$existing"; then
    status="NO SCHEMA - service never started, or its DB was never created"; live="-"; fail=1
  elif [ -z "$live" ] || [ "$live" = "NULL" ]; then
    status="NO SUCCESSFUL MIGRATION - every attempt failed"; live="-"; fail=1
  elif [ "$repo" = "$live" ]; then
    status="ok"
  elif [ "$live" -lt "$repo" ] 2>/dev/null; then
    status="STALE JAR - container is behind this checkout"; fail=1
  else
    status="AHEAD - this DB was written by NEWER code than this checkout"; fail=1
  fi

  # A FAILED migration leaves success=0, which the "latest successful" query skips - so a half-migrated
  # service can otherwise look merely behind. It is a different fault with a different fix, so name it.
  if [ "${nbad:-0}" != "0" ]; then
    status="$status  [+${nbad} FAILED migration(s)]"; fail=1
  fi

  printf '%-22s %-24s %6s %6s  %s\n' "$svc" "$db" "V$repo" "V$live" "$status"
done

echo
if [ "$fail" = 0 ]; then
  echo "All ${#services[@]} schemas match this checkout."
else
  cat <<'MSG'
MISMATCH - do not treat this deployment as done.

A container running a stale jar starts cleanly and passes its healthcheck; the schema version is the
only place the staleness is visible. Rebuild the affected service and recreate it:

    mvn -q -DskipTests -pl <service> -am clean install
    docker compose up -d --build <service>

For a FAILED migration, read the cause before retrying - Flyway will not re-apply a failed script until
the bad row is resolved:

    docker compose logs <service> | grep -i -A5 flyway
MSG
fi
exit "$fail"

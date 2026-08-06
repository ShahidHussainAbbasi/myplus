#!/usr/bin/env bash
# =============================================================================
# MyPlus - dump every database to a compressed, dated file. Intended for cron on the VPS.
#
#   ./backup-db.sh                     # write to ./backups
#   BACKUP_DIR=/srv/backups ./backup-db.sh
#
# Install as a daily 02:30 job (writes its own log so a silent failure is visible):
#   crontab -e
#   30 2 * * * cd /root/myplus/microservices && ./backup-db.sh >> /var/log/myplus-backup.log 2>&1
#
# WHY A LOGICAL DUMP, NOT A VOLUME COPY
# -------------------------------------
# Copying /var/lib/mysql while the server is running yields a file that LOOKS fine and may not restore -
# InnoDB has writes in flight. `mysqldump --single-transaction` takes a consistent snapshot without
# locking, so the dump is restorable and the shop keeps trading while it runs.
#
# All databases go into ONE file on purpose. They are interlinked - a sale in myplusdb references a
# product in myplusdb_catalog and a payment in myplusdb_finance. Per-database dumps taken at different
# moments restore into a state that never existed: an invoice pointing at a product row that is not
# there yet. One dump, one point in time.
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")"

BACKUP_DIR="${BACKUP_DIR:-./backups}"
KEEP_DAYS="${KEEP_DAYS:-14}"
STAMP="$(date +%F-%H%M)"
OUT="$BACKUP_DIR/myplus-$STAMP.sql.gz"

mkdir -p "$BACKUP_DIR"

# The password comes from .env - the same file compose interpolates, so there is one source of truth
# and no credential in the crontab.
if [ ! -f .env ]; then echo "FATAL: microservices/.env not found" >&2; exit 1; fi
DB_PASSWORD="$(grep -m1 '^DB_PASSWORD=' .env | cut -d= -f2-)"
if [ -z "$DB_PASSWORD" ]; then echo "FATAL: DB_PASSWORD is empty in .env" >&2; exit 1; fi

# Refuse to run if MySQL is not up, rather than writing a 0-byte file that looks like a backup.
if ! docker compose ps mysql --format '{{.State}}' 2>/dev/null | grep -q running; then
  echo "FATAL: the mysql container is not running - nothing to back up" >&2
  exit 1
fi

echo "[$(date +%T)] dumping all databases -> $OUT"

# MYSQL_PWD rather than -p<pw>: keeps the password out of the container's process list, and out of the
# "[Warning] Using a password on the command line interface can be insecure." noise.
docker compose exec -T -e "MYSQL_PWD=$DB_PASSWORD" mysql \
  mysqldump -uroot \
    --all-databases \
    --single-transaction \
    --quick \
    --routines --events --triggers \
  | gzip -9 > "$OUT"

# A dump that failed midway still leaves a gzip file. Check it is a valid archive AND that it contains
# the tenant data, so a broken backup is caught NOW and not on the day you need to restore it.
if ! gzip -t "$OUT" 2>/dev/null; then
  echo "FATAL: $OUT is not a valid gzip - the dump failed. Removing it." >&2
  rm -f "$OUT"; exit 1
fi
if ! zcat "$OUT" | grep -qm1 'CREATE DATABASE.*myplusdb'; then
  echo "FATAL: $OUT contains no myplusdb schema - refusing to keep a useless backup." >&2
  rm -f "$OUT"; exit 1
fi

SIZE="$(du -h "$OUT" | cut -f1)"
echo "[$(date +%T)] OK  $OUT  ($SIZE)"

# Rotate. Only ever deletes files this script's own naming scheme produced.
find "$BACKUP_DIR" -name 'myplus-*.sql.gz' -type f -mtime "+$KEEP_DAYS" -print -delete

echo "[$(date +%T)] retained backups:"
ls -lh "$BACKUP_DIR"/myplus-*.sql.gz 2>/dev/null | tail -5

# ---------------------------------------------------------------------------------------------------
# A backup you have never restored is a hope, not a backup. Rehearse it on a scratch host:
#
#   zcat myplus-2026-08-06-0230.sql.gz | docker compose exec -T -e "MYSQL_PWD=$DB_PASSWORD" mysql mysql -uroot
#
# Copy backups OFF this machine. A dump sitting on the same disk as the database does not survive the
# failure it exists to protect against:
#
#   rsync -az --delete ./backups/ user@elsewhere:/srv/myplus-backups/
# ---------------------------------------------------------------------------------------------------

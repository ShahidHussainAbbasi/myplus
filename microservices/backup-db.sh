#!/usr/bin/env bash
# =============================================================================
# MyPlus - dump every database to a compressed, dated file. Intended for cron on the VPS.
#
#   ./backup-db.sh                     # write to ./backups
#   BACKUP_DIR=/srv/backups ./backup-db.sh
#
# Install as a daily 02:30 job (writes its own log so a silent failure is visible):
#   crontab -e
#   30 2 * * * cd /opt/myplus/microservices && ./backup-db.sh >> /var/log/myplus-backup.log 2>&1
#
# WHAT THIS WRITES - THREE FILES, NOT ONE
# ---------------------------------------
#   myplus-<stamp>.sql.gz        the 16 databases
#   env-<stamp>.bak[.gpg]        the secrets (.env)
#   deployed-sha-<stamp>.txt     the git commit the running jars were built from
#
# Restoring the data ALONE does not bring the platform back, and finding that out during an incident is
# the wrong time. A restored database with a different JWT_SECRET invalidates every session; a different
# INTERNAL_SECRET makes every service reject the gateway's identity headers; and a dump whose schema is
# ahead of (or behind) the deployed jars fails Flyway validation at startup.
#
# These used to be documented as two extra commands to type by hand. A manual step in a nightly cron job
# is a step that never runs - so they are taken here, at the same moment as the dump.
#
# SECRETS: env-<stamp>.bak IS A SECRET FILE. It is written 0600, but that is not enough once you rsync
# the directory off-box. Set BACKUP_GPG_RECIPIENT to a public key id and it is encrypted instead -
# asymmetric, so no passphrase is needed at backup time and cron stays unattended:
#   BACKUP_GPG_RECIPIENT=ops@yourdomain ./backup-db.sh
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

# A dump that failed midway still leaves a gzip file. Three checks, cheapest first, so a broken backup is
# caught NOW and not on the day you need to restore it.
#
# 1. Is it a valid archive at all?
if ! gzip -t "$OUT" 2>/dev/null; then
  echo "FATAL: $OUT is not a valid gzip - the dump failed. Removing it." >&2
  rm -f "$OUT"; exit 1
fi

# 2. Did mysqldump actually FINISH? It writes "-- Dump completed on <date>" as its last line, and only on
#    success - so this is the check that distinguishes a complete backup from one that died at 90%. The
#    old version of this script never tested it: a dump truncated by a disk-full or a killed container
#    passed every check and sat in the backup directory looking healthy.
#    `tail` consumes the whole stream, so there is no early exit and no SIGPIPE (see 3).
if ! zcat "$OUT" | tail -5 | grep -q 'Dump completed'; then
  echo "FATAL: $OUT has no 'Dump completed' trailer - it is TRUNCATED. Removing it." >&2
  rm -f "$OUT"; exit 1
fi

# 3. Does it contain the tenant data?
#
#    NOTE THE SUBSHELL WITH `set +o pipefail` - it is load-bearing, not decoration.
#    `grep -qm1` exits the instant it finds a match, which hands `zcat` a SIGPIPE (exit 141). Under the
#    `set -o pipefail` at the top of this script, that makes the PIPELINE report failure even though the
#    match was found - so the guard concluded "no schema" and DELETED the backup it had just taken.
#    It only misfires once the dump is large enough for grep to finish before zcat does, i.e. it passed
#    on a small test database and destroyed the backup on a real one. Verified 2026-08-17 with a 983 KB
#    archive: guard failed, `rm -f "$OUT"` fired.
if ! ( set +o pipefail; zcat "$OUT" | grep -qm1 'CREATE DATABASE.*myplusdb' ); then
  echo "FATAL: $OUT contains no myplusdb schema - refusing to keep a useless backup." >&2
  rm -f "$OUT"; exit 1
fi

SIZE="$(du -h "$OUT" | cut -f1)"
echo "[$(date +%T)] OK  $OUT  ($SIZE)"

# ---------------------------------------------------------------------------------------------------
# 2 of 3 - THE SECRETS. Taken at the same instant as the dump, because a restored database with a
# different JWT_SECRET invalidates every session and a different INTERNAL_SECRET makes every service
# reject the gateway's identity headers. .env is git-ignored, so it exists on NO other machine.
# ---------------------------------------------------------------------------------------------------
ENV_OUT="$BACKUP_DIR/env-$STAMP.bak"
if [ -n "${BACKUP_GPG_RECIPIENT:-}" ]; then
  if ! command -v gpg >/dev/null 2>&1; then
    echo "FATAL: BACKUP_GPG_RECIPIENT is set but gpg is not installed" >&2; exit 1
  fi
  # Asymmetric, so no passphrase is needed here and cron stays unattended.
  gpg --batch --yes --trust-model always \
      --recipient "$BACKUP_GPG_RECIPIENT" --output "$ENV_OUT.gpg" --encrypt .env
  rm -f "$ENV_OUT"
  chmod 600 "$ENV_OUT.gpg"
  echo "[$(date +%T)] OK  $ENV_OUT.gpg  (encrypted for $BACKUP_GPG_RECIPIENT)"
else
  cp .env "$ENV_OUT"
  chmod 600 "$ENV_OUT"
  echo "[$(date +%T)] OK  $ENV_OUT  (0600, PLAINTEXT)"
  echo "         WARNING: this file holds DB_PASSWORD / JWT_SECRET / INTERNAL_SECRET / MAIL_PASSWORD."
  echo "         0600 protects it on THIS box and not one step further. Before it leaves the host, set"
  echo "         BACKUP_GPG_RECIPIENT=<key-id> so it is encrypted here instead of in transit."
fi

# ---------------------------------------------------------------------------------------------------
# 3 of 3 - THE CODE VERSION. The dump's schema matches the Flyway version of the jars that wrote it.
# Restoring a month-old dump onto today's jars (or the reverse) fails validation at startup, and the
# error names a checksum rather than the real problem, so record the SHA while it is free to know.
# ---------------------------------------------------------------------------------------------------
SHA_OUT="$BACKUP_DIR/deployed-sha-$STAMP.txt"
{
  if git rev-parse HEAD >/dev/null 2>&1; then
    echo "commit  $(git rev-parse HEAD)"
    echo "branch  $(git rev-parse --abbrev-ref HEAD)"
    echo "dirty   $(git status --porcelain | wc -l) uncommitted file(s)"
  else
    echo "commit  UNKNOWN - not a git checkout"
  fi
  echo "taken   $(date -Is)"
} > "$SHA_OUT"
echo "[$(date +%T)] OK  $SHA_OUT  ($(head -1 "$SHA_OUT"))"

# A dirty tree means the running jars may not correspond to ANY commit, so the SHA alone will not
# reproduce them. Say so now rather than during the restore.
if git rev-parse HEAD >/dev/null 2>&1 && [ "$(git status --porcelain | wc -l)" -gt 0 ]; then
  echo "         WARNING: the working tree has uncommitted changes. The recorded SHA does not fully"
  echo "         describe the deployed code - commit or tag before relying on this for recovery."
fi

# Rotate all three. Only ever deletes files this script's own naming schemes produced.
find "$BACKUP_DIR" \( -name 'myplus-*.sql.gz' -o -name 'env-*.bak' -o -name 'env-*.bak.gpg' \
                      -o -name 'deployed-sha-*.txt' \) -type f -mtime "+$KEEP_DAYS" -print -delete

echo "[$(date +%T)] retained backups:"
ls -lh "$BACKUP_DIR"/myplus-*.sql.gz 2>/dev/null | tail -5

# ---------------------------------------------------------------------------------------------------
# A backup you have never restored is a hope, not a backup. The FULL step-by-step procedure - verify,
# restore, roll forward, drill - is docs/deploy/DEPLOY-FULL-STACK.md section 8. Read it before you need it.
#
# The 30-second version, on a scratch container so it touches nothing real:
#
#   docker run -d --name verify-mysql -e MYSQL_ROOT_PASSWORD=verify mysql:8.0 && sleep 40
#   zcat backups/myplus-<stamp>.sql.gz | docker exec -i -e MYSQL_PWD=verify verify-mysql mysql -uroot
#   docker exec -e MYSQL_PWD=verify verify-mysql mysql -uroot -N \
#     -e "SELECT COUNT(*) FROM myplusdb_auth.users;"      # table is `users`, plural. Count ROWS, not bytes.
#   docker rm -f verify-mysql
#
# Copy backups OFF this machine. A dump sitting on the same disk as the database does not survive the
# failure it exists to protect against, and does not survive `docker volume rm` either:
#
#   rsync -az --delete ./backups/ user@elsewhere:/srv/myplus-backups/
#
# NOTE that ./backups now contains env-*.bak. Unless you set BACKUP_GPG_RECIPIENT, that rsync moves your
# production secrets in the clear to wherever it points.
# ---------------------------------------------------------------------------------------------------

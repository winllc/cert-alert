#!/usr/bin/env bash
# cert-alert - back up the database.
#
#   ./backup.sh                        to backups/cert-alert-<UTC time>.sql.gz
#   ./backup.sh --out FILE             to FILE (.sql.gz)
#   ./backup.sh --keep N               and remove all but the newest N in backups/
#   --if-running                       do nothing, successfully, when the database is not running
#   --engine docker|podman             as for install.sh
#
# A pg_dump of the bundled PostgreSQL, taken while cert-alert keeps running (one transaction,
# so it is consistent), gzipped, readable by its owner only. The database is the whole
# installation: the certificate cache, the audit trail, sessions, the license and this
# installation's id, API tokens and webhooks. What it does not hold is .env - the database
# password and CERT_ALERT_WEBHOOKS_SECRET_KEY, without which the webhooks' stored secrets
# cannot be read - so keep a copy of .env with the backups (somewhere as safe as they are).
# For a schedule, run it from cron or a systemd timer; ./restore.sh puts one back.
set -euo pipefail

cd "$(dirname "$0")"
PRODUCT=cert-alert
# shellcheck source-path=SCRIPTDIR source=container-engine.sh
. ./container-engine.sh

OUT=""
KEEP=""
IF_RUNNING=false
while [ $# -gt 0 ]; do
    case "$1" in
        --out) [ $# -ge 2 ] || { echo "--out needs a file" >&2; exit 2; }; OUT=$2; shift ;;
        --out=*) OUT=${1#--out=} ;;
        --keep) [ $# -ge 2 ] || { echo "--keep needs a number" >&2; exit 2; }; KEEP=$2; shift ;;
        --keep=*) KEEP=${1#--keep=} ;;
        --if-running) IF_RUNNING=true ;;
        --engine) [ $# -ge 2 ] || { echo "--engine needs docker or podman" >&2; exit 2; }; CONTAINER_ENGINE=$2; shift ;;
        --engine=*) CONTAINER_ENGINE=${1#--engine=} ;;
        -h|--help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
    esac
    shift
done
case "$KEEP" in ''|*[!0-9]*) [ -z "$KEEP" ] || { echo "--keep needs a number" >&2; exit 2; } ;; esac
[ "$KEEP" != 0 ] || { echo "--keep 0 would remove every backup" >&2; exit 2; }

say() { printf '%s\n' "$*"; }
die() { printf '\nerror: %s\n' "$*" >&2; exit 1; }

[ -f .env ] || die "no .env here: run this in the directory cert-alert was installed from."
select_engine > /dev/null

id=$(service_container postgres 2> /dev/null || true)
if [ -z "$id" ] || [ "$(engine inspect -f '{{.State.Running}}' "$id" 2> /dev/null)" != "true" ]; then
    [ "$IF_RUNNING" = true ] && { say "No running database to back up."; exit 0; }
    die "the database is not running (${COMPOSE_CMD[*]} up -d postgres starts it)."
fi
user=$(_engine_env_value CERT_ALERT_DB_USER); user=${user:-certalert}
db=$(_engine_env_value CERT_ALERT_DB_NAME); db=${db:-certalert}

if [ -z "$OUT" ]; then
    OUT="backups/${PRODUCT}-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
fi
mkdir -p "$(dirname "$OUT")"
umask 077
partial="$OUT.partial"
trap 'rm -f "$partial"' EXIT
say "Backing up database $db to $OUT ..."
engine exec "$id" pg_dump -U "$user" -d "$db" --no-password | gzip > "$partial"
gzip -t "$partial" || die "the backup in $partial is not a whole gzip file."
# Only the start is read: gzip then dies of a broken pipe, which pipefail would take for a failure.
[ "$( (gzip -dc "$partial" 2> /dev/null || true) | head -c 4096 | wc -c)" -gt 0 ] || die "pg_dump wrote nothing."
mv "$partial" "$OUT"
chmod 600 "$OUT"
trap - EXIT
say "Backed up to $OUT ($(wc -c < "$OUT" | tr -d ' ') bytes). Keep .env with it: the webhook secret key is there."

if [ -n "$KEEP" ]; then
    # The newest KEEP of this script's own backups; pre-upgrade backups are left alone.
    find backups -maxdepth 1 -name "${PRODUCT}-[0-9]*.sql.gz" -type f -print \
        | sort -r | tail -n +"$((KEEP + 1))" | while read -r old; do
            rm -f -- "$old"
            say "Removed $old"
        done
fi

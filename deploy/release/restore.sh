#!/usr/bin/env bash
# cert-alert - put a database backup back.
#
#   ./restore.sh FILE                  asks first, then restores FILE (.sql.gz from backup.sh,
#                                      or a backups/cert-alert-before-<version>-*.sql.gz that
#                                      an upgrade took)
#   ./restore.sh FILE --yes            without asking
#   --engine docker|podman             as for install.sh
#
# Replaces everything in the database with the backup: cert-alert is stopped, the database's
# schema emptied and the backup loaded - in one transaction, so a backup that does not load
# leaves the database as it was - and cert-alert started again. A backup from an earlier
# release is migrated forward as it starts. To go back to an earlier release, restore the
# backup its upgrade took with that release's files (its directory, its restore.sh), never
# with a newer one's. The .env must be the one the backup was taken with, or one with the
# same CERT_ALERT_WEBHOOKS_SECRET_KEY: the webhooks' secrets cannot be read without it.
set -euo pipefail

cd "$(dirname "$0")"
# shellcheck source-path=SCRIPTDIR source=container-engine.sh
. ./container-engine.sh

FILE=""
YES=false
while [ $# -gt 0 ]; do
    case "$1" in
        --yes|-y) YES=true ;;
        --engine) [ $# -ge 2 ] || { echo "--engine needs docker or podman" >&2; exit 2; }; CONTAINER_ENGINE=$2; shift ;;
        --engine=*) CONTAINER_ENGINE=${1#--engine=} ;;
        -h|--help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
        *) [ -z "$FILE" ] || { echo "one backup at a time" >&2; exit 2; }; FILE=$1 ;;
    esac
    shift
done

say() { printf '%s\n' "$*"; }
die() { printf '\nerror: %s\n' "$*" >&2; exit 1; }

[ -n "$FILE" ] || die "which backup? ./restore.sh backups/<file>.sql.gz (see --help)"
[ -f "$FILE" ] || die "$FILE is not a file."
[ -f .env ] || die "no .env here: run this in the directory cert-alert was installed from."
gzip -t "$FILE" || die "$FILE is not a whole gzip file."
# Only the start is read: gzip then dies of a broken pipe, which pipefail would take for a bad file.
header=$( (gzip -dc "$FILE" 2> /dev/null || true) | head -n 40)
case "$header" in
    *'PostgreSQL database dump'*) ;;
    *) die "$FILE does not look like a pg_dump backup." ;;
esac

select_engine
user=$(_engine_env_value CERT_ALERT_DB_USER); user=${user:-certalert}
db=$(_engine_env_value CERT_ALERT_DB_NAME); db=${db:-certalert}

if [ "$YES" != true ]; then
    [ -t 0 ] || die "not asking on a non-interactive input: pass --yes to restore without asking."
    say "This replaces everything in database $db with $FILE,"
    say "and stops cert-alert while it does. Type the database name ($db) to go on:"
    read -r answer
    [ "$answer" = "$db" ] || die "not restored."
fi

# The database up and answering; cert-alert stopped, so nothing writes while it is replaced.
compose up -d postgres
id=""
for _ in $(seq 1 60); do
    id=$(service_container postgres 2> /dev/null || true)
    if [ -n "$id" ] && engine exec "$id" pg_isready -U "$user" -d "$db" > /dev/null 2>&1; then
        break
    fi
    id=""
    sleep 2
done
[ -n "$id" ] || die "the database did not start (${COMPOSE_CMD[*]} logs postgres)."
compose stop cert-alert || true

say "Restoring $FILE into database $db ..."
# One transaction: the schema emptied, the backup loaded, or - on any error - neither.
{
    printf 'SET client_min_messages = warning;\nDROP SCHEMA IF EXISTS public CASCADE;\nCREATE SCHEMA public;\n'
    gzip -dc "$FILE"
} | engine exec -i "$id" psql -U "$user" -d "$db" --no-password -q -v ON_ERROR_STOP=1 --single-transaction > /dev/null \
    || die "the backup did not load; the database is as it was. cert-alert is stopped: ${COMPOSE_CMD[*]} up -d starts it."

say "Restored. Starting cert-alert ..."
compose up -d
say "Started. ${COMPOSE_CMD[*]} logs -f cert-alert shows it migrating (if the backup is from an earlier release) and coming up."

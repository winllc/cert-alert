#!/usr/bin/env bash
# Tests the customer bundle's scripts - install.sh (and so upgrade.sh), backup.sh and
# restore.sh - against a stand-in `docker` that answers as Docker and Docker Compose would and
# records what it was asked, so what the scripts decide can be checked without a container
# engine: a first install's .env, the refusal of plain HTTP to other hosts and its opt-in, an
# upgraded .env kept as it is, and backups taken and restored.
#
#   scripts/test-release-scripts.sh
#
# Run by scripts/check-release-bundle.sh (CI's release-bundle job).
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
BUNDLE="$REPO_ROOT/deploy/release"
WORK=$(mktemp -d "${TMPDIR:-/tmp}/test-release-scripts.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

FAILURES=0
pass() { printf '  ok   %s\n' "$*"; }
fail() { printf '  FAIL %s\n' "$*" >&2; FAILURES=$((FAILURES + 1)); }
check() { local what=$1; shift; if "$@"; then pass "$what"; else fail "$what"; fi; }

# --- the stand-in engine -------------------------------------------------------------------
STUB="$WORK/bin"
mkdir -p "$STUB"
cat > "$STUB/docker" <<'STUB_EOF'
#!/usr/bin/env bash
# Answers as Docker 27 with the Compose v2 plugin; every call is appended to $STUB_CALLS.
printf '%s\n' "$*" >> "$STUB_CALLS"
case "$1" in
    --version) echo "Docker version 27.0.0, build test" ;;
    info) exit 0 ;;
    compose)
        case "$2" in
            version) echo "Docker Compose version v2.29.0" ;;
            *) exit 0 ;;
        esac ;;
    image) exit 0 ;;
    load) exit 0 ;;
    logs) exit 0 ;;
    ps)
        case "$*" in
            *service=postgres*) echo "cid-postgres" ;;
            *service=cert-alert*) echo "cid-cert-alert" ;;
        esac ;;
    inspect)
        case "$*" in
            *State.Running*) echo "${STUB_PG_RUNNING:-true}" ;;
            *) echo healthy ;;
        esac ;;
    exec)
        case "$*" in
            *pg_dump*) printf -- '--\n-- PostgreSQL database dump\n--\nCREATE TABLE stand_in (id integer);\n' ;;
            *pg_isready*) exit 0 ;;
            *psql*) cat > "$STUB_PSQL_IN"; exit "${STUB_PSQL_EXIT:-0}" ;;
        esac ;;
esac
exit 0
STUB_EOF
chmod +x "$STUB/docker"
export PATH="$STUB:$PATH" STUB_CALLS="$WORK/calls" STUB_PSQL_IN="$WORK/psql.in"
unset CONTAINER_ENGINE

# A bundle as it unpacks.
DIR="$WORK/cert-alert-9.9.9"
mkdir -p "$DIR"
cp "$BUNDLE/install.sh" "$BUNDLE/upgrade.sh" "$BUNDLE/backup.sh" "$BUNDLE/restore.sh" \
    "$BUNDLE/container-engine.sh" "$BUNDLE/env.template" "$BUNDLE/docker-compose.yml" "$DIR/"
echo 9.9.9 > "$DIR/VERSION"

OUT="$WORK/out"
run() { : > "$STUB_CALLS"; (cd "$DIR" && "$@") > "$OUT" 2>&1; }
output_has() { grep -qF -- "$1" "$OUT"; }
called() { grep -qF -- "$1" "$STUB_CALLS"; }
env_line() { grep -qxF -- "$1" "$DIR/.env"; }
set_env() { sed -i -e "s|^#\{0,1\}[[:space:]]*$1=.*|$1=$2|" "$DIR/.env"; grep -q "^$1=" "$DIR/.env" || echo "$1=$2" >> "$DIR/.env"; }
drop_env() { sed -i -e "/^#\{0,1\}[[:space:]]*$1=/d" "$DIR/.env"; }
digest() { sha256sum "$DIR/.env" | cut -d' ' -f1; }

echo "first install"
check "creates .env and stops for it to be edited" run ./install.sh
check ".env is the owner's only" test "$(stat -c %a "$DIR/.env")" = 600
check "a random database password" grep -qE '^CERT_ALERT_DB_PASSWORD=[0-9a-f]{48}$' "$DIR/.env"
key=$(sed -n 's/^CERT_ALERT_WEBHOOKS_SECRET_KEY=//p' "$DIR/.env")
check "a random webhook secret key, 32 bytes of base64" test "$(printf '%s' "$key" | base64 -d 2> /dev/null | wc -c)" = 32
check "the engine it chose" env_line CONTAINER_ENGINE=docker
check "plain HTTP not allowed" env_line CERT_ALERT_ALLOW_PLAIN_HTTP=false
check "says how it is to be reached" output_has "CERT_ALERT_ALLOW_PLAIN_HTTP=true"
check "nothing started" bash -c "! grep -q 'compose up' '$STUB_CALLS'"
second_key=$( (cd "$WORK" && mkdir -p again && cp "$DIR"/{install.sh,container-engine.sh,env.template,VERSION} again/ \
    && cd again && ./install.sh > /dev/null 2>&1 && sed -n 's/^CERT_ALERT_WEBHOOKS_SECRET_KEY=//p' .env) )
check "a different key for another installation" test -n "$second_key" -a "$second_key" != "$key"

echo "plain HTTP to other hosts"
set_env CERT_ALERT_LDAP_PASSWORD secret
set_env CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS alice
check "refused while TLS is off on 0.0.0.0" bash -c "! (cd '$DIR' && ./install.sh) > '$OUT' 2>&1"
check "says why and what to set" output_has "plain HTTP on 0.0.0.0 refused"
check "and starts nothing" bash -c "! grep -q 'compose up' '$STUB_CALLS'"
set_env CERT_ALERT_ALLOW_PLAIN_HTTP true
check "starts with CERT_ALERT_ALLOW_PLAIN_HTTP=true" run ./install.sh
check "and warns" output_has "Allowed because .env sets CERT_ALERT_ALLOW_PLAIN_HTTP=true"
check "compose up was run" called "compose up -d --remove-orphans"
set_env CERT_ALERT_ALLOW_PLAIN_HTTP false
set_env CERT_ALERT_BIND_ADDRESS 127.0.0.1
check "starts on 127.0.0.1 without the opt-in" run ./install.sh
check "with no warning" bash -c "! grep -q WARNING '$OUT'"
set_env CERT_ALERT_BIND_ADDRESS 0.0.0.0
mkdir -p "$DIR/tls" && touch "$DIR/tls/keystore.p12"
set_env CERT_ALERT_TLS_ENABLED true
set_env CERT_ALERT_KEYSTORE file:/etc/cert-alert/tls/keystore.p12
set_env CERT_ALERT_TRUSTSTORE file:/etc/cert-alert/tls/keystore.p12
check "starts on 0.0.0.0 with TLS on" run ./install.sh
set_env CERT_ALERT_TLS_ENABLED false

echo "upgrade of an .env from before CERT_ALERT_ALLOW_PLAIN_HTTP"
drop_env CERT_ALERT_ALLOW_PLAIN_HTTP
drop_env CERT_ALERT_WEBHOOKS_SECRET_KEY
before=$(digest)
check "upgrades, plain HTTP on 0.0.0.0 as before" run ./upgrade.sh --no-backup
check "warns, saying how to accept it explicitly" output_has "release before CERT_ALERT_ALLOW_PLAIN_HTTP"
check "says the webhook key is missing" output_has "CERT_ALERT_WEBHOOKS_SECRET_KEY is not set"
check ".env is left as it was" test "$(digest)" = "$before"
check "re-running install.sh on it starts it too" run ./install.sh

echo "backups"
check "upgrade.sh backs up first" run ./upgrade.sh
upgrade_backup=$(find "$DIR/backups" -name 'cert-alert-before-9.9.9-*.sql.gz' | head -n 1)
check "into backups/, named for the release" test -n "$upgrade_backup"
check "readable by the owner only" test "$(stat -c %a "$upgrade_backup")" = 600
check "a pg_dump" bash -c "gzip -dc '$upgrade_backup' | grep -q 'PostgreSQL database dump'"
check "backup.sh" run ./backup.sh
sleep 1
check "backup.sh --keep 1" run ./backup.sh --keep 1
check "keeps the newest of its own, and the upgrade's" test "$(find "$DIR/backups" -name 'cert-alert-2*.sql.gz' | wc -l)" = 1 -a -f "$upgrade_backup"
check "to a file named" run ./backup.sh --out "$WORK/mine.sql.gz"
check "there" test -s "$WORK/mine.sql.gz"
export STUB_PG_RUNNING=false
check "refuses with no running database" bash -c "! (cd '$DIR' && ./backup.sh) > '$OUT' 2>&1"
check "unless --if-running" run ./backup.sh --if-running
unset STUB_PG_RUNNING

echo "restore"
check "asks first, and will not ask a pipe" bash -c "! (cd '$DIR' && ./restore.sh '$WORK/mine.sql.gz' < /dev/null) > '$OUT' 2>&1"
check "touching nothing" bash -c "! grep -q 'exec -i' '$STUB_CALLS'"
printf 'not a dump\n' | gzip > "$WORK/not-a-dump.sql.gz"
check "refuses what is not a pg_dump" bash -c "! (cd '$DIR' && ./restore.sh '$WORK/not-a-dump.sql.gz' --yes) > '$OUT' 2>&1"
check "restores with --yes" run ./restore.sh "$WORK/mine.sql.gz" --yes
# A real database's dump runs past a pipe buffer: reading only its start must not count as a failure.
{ printf -- '--\n-- PostgreSQL database dump\n--\n'; head -c 4000000 /dev/zero | tr '\0' 'x' | fold -w 100; } | gzip > "$WORK/large.sql.gz"
check "takes a backup larger than a pipe buffer" run ./restore.sh "$WORK/large.sql.gz" --yes
check "restores with --yes again" run ./restore.sh "$WORK/mine.sql.gz" --yes
check "the schema emptied first" bash -c "head -n 2 '$STUB_PSQL_IN' | tail -n 1 | grep -qx 'DROP SCHEMA IF EXISTS public CASCADE;'"
check "then the backup" grep -q 'CREATE TABLE stand_in' "$STUB_PSQL_IN"
check "in one transaction" grep -q -- '--single-transaction' "$STUB_CALLS"
check "cert-alert stopped before, started after" bash -c "
    stop=\$(grep -n 'compose stop cert-alert' '$STUB_CALLS' | cut -d: -f1)
    load=\$(grep -n 'exec -i' '$STUB_CALLS' | cut -d: -f1)
    up=\$(grep -nx 'compose up -d' '$STUB_CALLS' | cut -d: -f1)
    [ -n \"\$stop\" ] && [ -n \"\$load\" ] && [ -n \"\$up\" ] && [ \"\$stop\" -lt \"\$load\" ] && [ \"\$load\" -lt \"\$up\" ]"
export STUB_PSQL_EXIT=3
check "a backup that does not load fails" bash -c "! (cd '$DIR' && ./restore.sh '$WORK/mine.sql.gz' --yes) > '$OUT' 2>&1"
check "and leaves cert-alert stopped, saying so" output_has "the database is as it was"
unset STUB_PSQL_EXIT

if [ "$FAILURES" -gt 0 ]; then
    echo "test-release-scripts: $FAILURES failed" >&2
    exit 1
fi
echo "test-release-scripts: all passed"

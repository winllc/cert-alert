#!/usr/bin/env bash
# cert-alert - install, and upgrade.
#
#   ./install.sh                       first install; run again after editing .env
#   ./install.sh --upgrade [--from DIR] [--no-backup]
#                                      the same as ./upgrade.sh
#   --engine docker|podman             which container engine (or CONTAINER_ENGINE=...);
#                                      by default the one in .env, else Docker if its
#                                      daemon answers, else Podman
#
# Safe to run again. It never overwrites an .env (except to set ENS_LICENSE from
# license/license.lic), or a different license already installed.
set -euo pipefail

cd "$(dirname "$0")"
VERSION=$(cat VERSION)
PRODUCT=cert-alert
# shellcheck source-path=SCRIPTDIR source=container-engine.sh
. ./container-engine.sh

MODE=install
FROM=""
BACKUP=true
while [ $# -gt 0 ]; do
    case "$1" in
        --upgrade) MODE=upgrade ;;
        --from) [ $# -ge 2 ] || { echo "--from needs a directory" >&2; exit 2; }; FROM=$2; shift ;;
        --from=*) FROM=${1#--from=} ;;
        --no-backup) BACKUP=false ;;
        --engine) [ $# -ge 2 ] || { echo "--engine needs docker or podman" >&2; exit 2; }; CONTAINER_ENGINE=$2; shift ;;
        --engine=*) CONTAINER_ENGINE=${1#--engine=} ;;
        -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
    esac
    shift
done

say() { printf '%s\n' "$*"; }
die() { printf '\nerror: %s\n' "$*" >&2; exit 1; }

# The value of NAME in .env as compose would read it, without the quotes; empty if unset.
env_value() {
    local v
    v=$(grep -E "^[[:space:]]*$1=" .env | tail -n 1 | cut -d= -f2- || true)
    v=${v%\"}; v=${v#\"}; v=${v%\'}; v=${v#\'}
    printf '%s' "$v"
}

load_images() {
    local f ref all_present=true
    # images/IMAGES lists what the archive holds; skip the slow load when all are here.
    if [ -f images/IMAGES ]; then
        while read -r ref; do
            [ -n "$ref" ] || continue
            engine image inspect "$ref" > /dev/null 2>&1 || all_present=false
        done < images/IMAGES
        if [ "$all_present" = true ]; then
            say "Images already loaded: $(tr '\n' ' ' < images/IMAGES)"
            return 0
        fi
    fi
    for f in images/*.tar; do
        [ -e "$f" ] || { say "No images/ in this bundle - they will be pulled from the registry."; return 0; }
        say "Loading images from $f (this takes a minute) ..."
        engine load -i "$f"
    done
}

make_directories() {
    # tls and email-templates are mounted read-only into the application, and created here
    # so the engine does not create them as root-owned directories the first time it starts.
    # license is where a license file can be put for this script to install.
    mkdir -p license tls email-templates
}

random_password() {
    od -An -tx1 -N24 /dev/urandom | tr -d ' \n'
}

# A random AES-256 key, base64: the webhooks' secret key.
random_key() {
    head -c 32 /dev/urandom | base64 | tr -d '\n'
}

create_env() {
    cp env.template .env
    chmod 600 .env
    local pw key tmp
    pw=$(random_password)
    key=$(random_key)
    tmp=$(mktemp .env.XXXXXX)
    # The engine chosen now, so that an upgrade on a host with both uses the same one. Base64
    # has no '|', so it is the delimiter for the key.
    sed -e "s/^CERT_ALERT_DB_PASSWORD=\$/CERT_ALERT_DB_PASSWORD=${pw}/" \
        -e "s|^#[[:space:]]*CERT_ALERT_WEBHOOKS_SECRET_KEY=\$|CERT_ALERT_WEBHOOKS_SECRET_KEY=${key}|" \
        -e "s/^#[[:space:]]*CONTAINER_ENGINE=.*/CONTAINER_ENGINE=${ENGINE}/" .env > "$tmp"
    chmod 600 "$tmp"
    mv "$tmp" .env
}

explain_next_steps() {
    cat <<EOF

Created $(pwd)/.env from env.template, with a random database password and webhook
secret key, for $ENGINE (CONTAINER_ENGINE in .env). Keep a copy of .env with your backups.

Before cert-alert can start, edit .env and set at least:

  CERT_ALERT_LDAP_URL                     your directory, e.g. ldaps://directory.example.gov:636
  CERT_ALERT_LDAP_BASE                    the search base, e.g. dc=example,dc=gov
  CERT_ALERT_LDAP_USER / _PASSWORD        the service account it binds as
  CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS   who administers it (uids; addresses are ignored)

and choose how it is reached (it is published on 0.0.0.0, port 8080; plain HTTP to other
hosts is refused unless you say so):

  CERT_ALERT_TLS_ENABLED=true             HTTPS from cert-alert itself (keystores in $(pwd)/tls); or
  CERT_ALERT_BIND_ADDRESS=127.0.0.1       this host only, behind a TLS-terminating proxy of yours; or
  CERT_ALERT_ALLOW_PLAIN_HTTP=true        plain HTTP to other hosts: a lab, or a network that encrypts

and, if you use them, SMTP (SPRING_MAIL_*) and the banner. Every setting is described in .env.

cert-alert needs an ENS license (from the ENS licensing portal: an evaluation, or the
license issued against your order). Either put it at

  $(pwd)/license/license.lic

and this script installs it when it starts cert-alert, or upload it afterwards on the
license screen (Administration -> License, /admin/license). Until one is installed, only
signing in and the license screen work.

Then run ./install.sh again to start it.
EOF
}

check_env() {
    local missing="" name
    for name in CERT_ALERT_LDAP_URL CERT_ALERT_LDAP_BASE CERT_ALERT_DB_PASSWORD; do
        [ -n "$(env_value "$name")" ] || missing="$missing $name"
    done
    # Anything still carrying the template's placeholder.
    local placeholders
    placeholders=$(grep -E '^[[:space:]]*[A-Z0-9_]+=.*CHANGE_ME' .env | cut -d= -f1 | tr '\n' ' ' || true)
    if [ -n "$missing" ] || [ -n "$placeholders" ]; then
        [ -z "$missing" ] || say "Not set in .env:$missing" >&2
        [ -z "$placeholders" ] || say "Still CHANGE_ME in .env: $placeholders" >&2
        die "edit $(pwd)/.env, then run this again."
    fi
    if [ "$(env_value CERT_ALERT_TLS_ENABLED)" = "true" ]; then
        [ -n "$(ls -A tls 2> /dev/null)" ] \
            || die "CERT_ALERT_TLS_ENABLED=true but $(pwd)/tls is empty - put the keystore and truststore there."
        for name in CERT_ALERT_KEYSTORE CERT_ALERT_TRUSTSTORE; do
            [ -n "$(env_value "$name")" ] \
                || die "CERT_ALERT_TLS_ENABLED=true needs $name (file:/etc/cert-alert/tls/<file>) in .env."
        done
    fi
}

# Whether NAME is set (uncommented) in .env at all, even to an empty value.
env_has() {
    grep -qE "^[[:space:]]*$1=" .env
}

keep_previous_listen_address() {
    # New installs listen on every interface (0.0.0.0). An existing .env keeps the address it
    # already had, so an upgrade never opens a 127.0.0.1-only install to the network:
    #   - CERT_ALERT_BIND_ADDRESS set in .env: used as it is;
    #   - not set, in an .env from the release that listened on 127.0.0.1 by default (it
    #     mentions CERT_ALERT_ALLOW_PLAIN_HTTP, and not CONTAINER_ENGINE): that 127.0.0.1 is
    #     written into .env;
    #   - not set, in an .env from an earlier release: those listened on 0.0.0.0, as this one
    #     does, so nothing changes.
    env_has CERT_ALERT_BIND_ADDRESS && return 0
    grep -qE '^#?[[:space:]]*CONTAINER_ENGINE=' .env && return 0
    grep -qE '^#?[[:space:]]*CERT_ALERT_ALLOW_PLAIN_HTTP=' .env || return 0
    {
        printf '\n# Added by install.sh %s, so this upgrade changes nothing: the release this .env\n' "$VERSION"
        printf '# was written for listened on 127.0.0.1 by default. New installs listen on 0.0.0.0 -\n'
        printf '# see the upgrade notes in the README.\n'
        printf 'CERT_ALERT_BIND_ADDRESS=127.0.0.1\n'
    } >> .env
    say "Kept the previous listen address: added CERT_ALERT_BIND_ADDRESS=127.0.0.1 to .env."
}

# On an upgrade, say so when this install stays on this host only, as it was.
note_listen_address() {
    local bind
    bind=$(env_value CERT_ALERT_BIND_ADDRESS); bind=${bind:-0.0.0.0}
    case "$bind" in
        127.*|localhost|::1|'[::1]')
            say "Note: cert-alert stays on $bind (CERT_ALERT_BIND_ADDRESS in .env), as before. New installs"
            say "      of this release listen on 0.0.0.0; set CERT_ALERT_BIND_ADDRESS=0.0.0.0 to publish it"
            say "      to other hosts, with TLS on or a TLS-terminating proxy in front." ;;
    esac
}

check_exposure() {
    # Plain HTTP on an interface other hosts reach sends passwords and session cookies across
    # the network in clear text. Refused unless .env says CERT_ALERT_ALLOW_PLAIN_HTTP=true, and
    # never silent even then. An .env from a release before that setting (it does not set it at
    # all) is upgraded as it is: warned about, not refused.
    [ "$(env_value CERT_ALERT_TLS_ENABLED)" = "true" ] && return 0
    local bind port allow
    bind=$(env_value CERT_ALERT_BIND_ADDRESS); bind=${bind:-0.0.0.0}
    port=$(env_value CERT_ALERT_PORT); port=${port:-8080}
    case "$bind" in
        127.*|localhost|::1|'[::1]') return 0 ;;
    esac
    allow=$(env_value CERT_ALERT_ALLOW_PLAIN_HTTP)
    if [ "$allow" != true ] && env_has CERT_ALERT_ALLOW_PLAIN_HTTP; then
        say "" >&2
        say "Not starting cert-alert: it would serve plain HTTP to other hosts - TLS is off (CERT_ALERT_TLS_ENABLED" >&2
        say "is not true) and it is published on $bind (CERT_ALERT_BIND_ADDRESS), port $port - and directory" >&2
        say "passwords and session cookies would cross the network in clear text. In .env, either:" >&2
        say "  - turn TLS on in the application: CERT_ALERT_TLS_ENABLED=true, keystores in tls/; or" >&2
        say "  - put a TLS-terminating reverse proxy on this host in front: CERT_ALERT_BIND_ADDRESS=127.0.0.1," >&2
        say "    SERVER_FORWARD_HEADERS_STRATEGY=framework and ENS_COOKIE_SECURE=true; or" >&2
        say "  - accept plain HTTP (a lab, or a network that encrypts for you): CERT_ALERT_ALLOW_PLAIN_HTTP=true." >&2
        die "plain HTTP on $bind refused; edit $(pwd)/.env, then run this again."
    fi
    say "" >&2
    say "WARNING: cert-alert is reachable over plain HTTP: TLS is off (CERT_ALERT_TLS_ENABLED is not" >&2
    say "         true) and it is published on $bind (CERT_ALERT_BIND_ADDRESS), port $port. Directory" >&2
    say "         passwords and session cookies cross the network in clear text. Recommended:" >&2
    say "           - turn TLS on in the application (CERT_ALERT_TLS_ENABLED=true, keystores in tls/); or" >&2
    say "           - put a TLS-terminating reverse proxy in front and set" >&2
    say "             CERT_ALERT_BIND_ADDRESS=127.0.0.1, SERVER_FORWARD_HEADERS_STRATEGY=framework" >&2
    say "             and ENS_COOKIE_SECURE=true." >&2
    if [ "$allow" = true ]; then
        say "         (Allowed because .env sets CERT_ALERT_ALLOW_PLAIN_HTTP=true.)" >&2
    else
        say "         This .env is from a release before CERT_ALERT_ALLOW_PLAIN_HTTP, so it is kept as it is;" >&2
        say "         new installations refuse this. Add CERT_ALERT_ALLOW_PLAIN_HTTP=true to .env to accept" >&2
        say "         it in as many words, or one of the above." >&2
    fi
    say "" >&2
}

note_webhook_key() {
    # An .env from a release that did not generate the key: said, not changed.
    env_has CERT_ALERT_WEBHOOKS_SECRET_KEY && [ -n "$(env_value CERT_ALERT_WEBHOOKS_SECRET_KEY)" ] && return 0
    say "Note: CERT_ALERT_WEBHOOKS_SECRET_KEY is not set in .env, so webhook signing secrets and URLs are"
    say "      stored unencrypted. New installations get one; to encrypt yours, add"
    say "      CERT_ALERT_WEBHOOKS_SECRET_KEY=<the output of: openssl rand -base64 32> to .env (keep a copy)."
}

check_port() {
    # Rootless Podman cannot publish a port below net.ipv4.ip_unprivileged_port_start (1024
    # unless lowered), and says so only as a failure to start.
    engine_rootless || return 0
    local port lowest
    port=$(env_value CERT_ALERT_PORT); port=${port:-8080}
    lowest=$(sysctl -n net.ipv4.ip_unprivileged_port_start 2> /dev/null || echo 1024)
    case "$port" in *[!0-9]*|'') return 0 ;; esac
    [ "$port" -ge "$lowest" ] \
        || die "rootless Podman cannot publish port $port (below $lowest): set CERT_ALERT_PORT=8080 or another port from $lowest up in .env, or lower net.ipv4.ip_unprivileged_port_start."
}

check_license() {
    if [ -f license/license.lic ]; then
        say "License: license/license.lic found; it goes into .env as ENS_LICENSE, which cert-alert installs as it"
        say "starts unless a different usable license, or a newer revision of it, is installed already."
    else
        say "License: no license/license.lic here. If none is installed yet, upload one on the"
        say "license screen once cert-alert is up (see below); until then only sign-in and that screen work."
    fi
}

license_to_env() {
    # Gives license/license.lic to cert-alert as ENS_LICENSE in .env (the file, base64), before it
    # starts. cert-alert keeps its license in the database and reads the license volume once, on
    # its first license check while the database has no installation id, so a file copied into the
    # volume once it runs would never be read. ENS_LICENSE is installed on the first license check
    # after the start when no usable license is installed yet, or when it is a newer revision of
    # the installed one; any other installed license (an upload on the license screen) is kept, so
    # the value can stay. A newer file in license/ replaces it on the next run.
    [ -f license/license.lic ] || return 0
    if ! grep -q -- '-----BEGIN ENS LICENSE-----' license/license.lic; then
        say "License: license/license.lic is not an ENS license file (download it again from the ENS licensing portal); not used."
        return 0
    fi
    local value current tmp
    value=$(base64 < license/license.lic | tr -d '\n')
    current=$(env_value ENS_LICENSE)
    if [ "$current" = "$value" ]; then
        say "License: .env already gives license/license.lic as ENS_LICENSE."
        return 0
    fi
    # Rewritten in place (cat >), so .env keeps its owner and mode; awk -v is safe for base64.
    tmp=$(mktemp .env.XXXXXX)
    awk -v v="$value" '
        /^[[:space:]]*ENS_LICENSE=/ { if (!done) print "ENS_LICENSE=" v; done = 1; next }
        { print }
        END {
            if (!done) {
                print ""
                print "# The ENS license, from license/license.lic (base64), set by install.sh."
                print "ENS_LICENSE=" v
            }
        }' .env > "$tmp"
    cat "$tmp" > .env
    rm -f "$tmp"
    say "License: set ENS_LICENSE in .env from license/license.lic; cert-alert installs it as it starts."
}

newer_settings() {
    # Settings this release documents that .env does not mention at all, so an upgrade
    # does not hide what is new. Informational only.
    local names name new=""
    names=$(grep -oE '^#?[[:space:]]*[A-Z][A-Z0-9_]+=' env.template | tr -d '#= ' | sort -u)
    for name in $names; do
        grep -qE "^#?[[:space:]]*${name}=" .env || new="$new $name"
    done
    if [ -n "$new" ]; then
        say "New settings in this release (see env.template; defaults apply until you set them):"
        for name in $new; do say "  $name"; done
    fi
}

copy_from_previous() {
    [ -n "$FROM" ] || return 0
    [ -d "$FROM" ] || die "--from $FROM is not a directory."
    [ -f "$FROM/.env" ] || die "--from $FROM has no .env - is it the previous release's directory?"
    if [ -f .env ]; then
        say "Keeping the .env already here (not copying $FROM/.env)."
    else
        cp -p "$FROM/.env" .env
        chmod 600 .env
        say "Copied .env from $FROM."
    fi
    # An override of the operator's own (e.g. docker-compose.no-ports.yml copied to it).
    if [ -f "$FROM/docker-compose.override.yml" ] && [ ! -e docker-compose.override.yml ]; then
        cp -p "$FROM/docker-compose.override.yml" docker-compose.override.yml
        say "Copied docker-compose.override.yml from $FROM."
    fi
    local d
    for d in license tls email-templates; do
        if [ -d "$FROM/$d" ]; then
            mkdir -p "$d"
            # -n: never replace a file already here.
            cp -Rpn "$FROM/$d/." "$d/"
        fi
    done
    say "Copied license/, tls/ and email-templates/ from $FROM (existing files kept)."
    say "The installed license, and this installation's id, are in the ens-license volume, which is kept."
}

backup_database() {
    [ "$BACKUP" = true ] || { say "Skipping the database backup (--no-backup)."; return 0; }
    # backup.sh, with the engine already chosen; nothing to do when no database is running.
    CONTAINER_ENGINE=$ENGINE ./backup.sh --if-running \
        --out "backups/${PRODUCT}-before-${VERSION}-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
}

wait_until_healthy() {
    local id status _
    id=$(service_container cert-alert)
    [ -n "$id" ] || die "no cert-alert container was started. See: ${COMPOSE_CMD[*]} logs cert-alert"
    say "Waiting for cert-alert to report healthy ..."
    for _ in $(seq 1 60); do
        # Podman runs health checks from systemd timers; running one here as well means the
        # status is current without waiting for the next tick.
        [ "$ENGINE" != podman ] || engine healthcheck run "$id" > /dev/null 2>&1 || true
        status=$(engine inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2> /dev/null || echo unknown)
        case "$status" in
            healthy|running)
                local port scheme=http
                port=$(env_value CERT_ALERT_PORT); port=${port:-8080}
                [ "$(env_value CERT_ALERT_TLS_ENABLED)" = "true" ] && scheme=https
                local url bind host
                bind=$(env_value CERT_ALERT_BIND_ADDRESS); bind=${bind:-0.0.0.0}
                case "$bind" in
                    0.0.0.0|::|'[::]'|'*') host=$(hostname -f 2> /dev/null || hostname) ;;
                    *) host=$bind ;;
                esac
                url="${scheme}://${host}:${port}"
                say ""
                say "cert-alert $VERSION is up: ${url}/"
                case "$bind" in
                    127.*|localhost|::1|'[::1]') say "(This host only: point your TLS-terminating reverse proxy at it.)" ;;
                esac
                say "License screen (installation id, upload): ${url}/admin/license"
                say "Sign in as one of CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS and press Sync directory."
                return 0 ;;
            unhealthy|exited|dead) break ;;
        esac
        sleep 5
    done
    engine logs --tail 50 "$id" >&2 || true
    die "cert-alert did not become healthy (status: $status). See: ${COMPOSE_CMD[*]} logs cert-alert"
}

start() {
    license_to_env
    compose config --quiet || die "the compose configuration is not valid with this .env."
    compose up -d --remove-orphans
    wait_until_healthy
}

say "cert-alert $VERSION - $MODE"

if [ "$MODE" = install ]; then
    make_directories
    select_engine
    if [ ! -f .env ]; then
        load_images
        create_env
        explain_next_steps
        exit 0
    fi
    say "Using the existing .env (not overwritten)."
    keep_previous_listen_address
    check_env
    check_exposure
    note_webhook_key
    check_port
    check_license
    load_images
    start
else
    copy_from_previous
    [ -f .env ] || die "no .env here. Pass --from <the previous release's directory>, or copy its .env, license/ and tls/ here. For a first install run ./install.sh."
    select_engine
    make_directories
    newer_settings
    keep_previous_listen_address
    note_listen_address
    check_env
    check_exposure
    note_webhook_key
    check_port
    check_license
    load_images
    backup_database
    start
    say "Images of the previous release are still on this host; '$ENGINE image prune' removes unused ones."
fi

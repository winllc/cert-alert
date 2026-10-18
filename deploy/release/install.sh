#!/usr/bin/env bash
# cert-alert - install, and upgrade.
#
#   ./install.sh                       first install; run again after editing .env
#   ./install.sh --upgrade [--from DIR] [--no-backup]
#                                      the same as ./upgrade.sh
#
# Safe to run again. It never overwrites an .env, or a license already installed in the
# application's license volume.
set -euo pipefail

cd "$(dirname "$0")"
VERSION=$(cat VERSION)
PRODUCT=cert-alert

MODE=install
FROM=""
BACKUP=true
while [ $# -gt 0 ]; do
    case "$1" in
        --upgrade) MODE=upgrade ;;
        --from) [ $# -ge 2 ] || { echo "--from needs a directory" >&2; exit 2; }; FROM=$2; shift ;;
        --from=*) FROM=${1#--from=} ;;
        --no-backup) BACKUP=false ;;
        -h|--help) sed -n '2,9p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
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

check_prerequisites() {
    command -v docker > /dev/null 2>&1 || die "docker is not installed, or not on PATH."
    docker compose version > /dev/null 2>&1 \
        || die "the Docker Compose plugin (docker compose, v2) is needed."
    docker info > /dev/null 2>&1 \
        || die "cannot talk to the Docker daemon - is it running, and may this user use it?"
}

load_images() {
    local f ref all_present=true
    # images/IMAGES lists what the archive holds; skip the slow load when all are here.
    if [ -f images/IMAGES ]; then
        while read -r ref; do
            [ -n "$ref" ] || continue
            docker image inspect "$ref" > /dev/null 2>&1 || all_present=false
        done < images/IMAGES
        if [ "$all_present" = true ]; then
            say "Images already loaded: $(tr '\n' ' ' < images/IMAGES)"
            return 0
        fi
    fi
    for f in images/*.tar; do
        [ -e "$f" ] || { say "No images/ in this bundle - they will be pulled from the registry."; return 0; }
        say "Loading images from $f (this takes a minute) ..."
        docker load -i "$f"
    done
}

make_directories() {
    # tls and email-templates are mounted read-only into the application, and created here
    # so Docker does not create them as root-owned directories the first time it starts.
    # license is where a license file can be put for this script to install.
    mkdir -p license tls email-templates
}

random_password() {
    od -An -tx1 -N24 /dev/urandom | tr -d ' \n'
}

create_env() {
    cp env.template .env
    chmod 600 .env
    local pw tmp
    pw=$(random_password)
    tmp=$(mktemp .env.XXXXXX)
    sed "s/^CERT_ALERT_DB_PASSWORD=\$/CERT_ALERT_DB_PASSWORD=${pw}/" .env > "$tmp"
    chmod 600 "$tmp"
    mv "$tmp" .env
}

explain_next_steps() {
    cat <<EOF

Created $(pwd)/.env from env.template, with a random database password.

Before cert-alert can start, edit .env and set at least:

  CERT_ALERT_LDAP_URL                     your directory, e.g. ldaps://directory.example.gov:636
  CERT_ALERT_LDAP_BASE                    the search base, e.g. dc=example,dc=gov
  CERT_ALERT_LDAP_USER / _PASSWORD        the service account it binds as
  CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS   who administers it (uids; addresses are ignored)

and, if you use them, SMTP (SPRING_MAIL_*), TLS (CERT_ALERT_TLS_* with keystores in
$(pwd)/tls) and the banner. Every setting is described in .env.

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

warn_if_exposed() {
    # Plain HTTP on every interface sends passwords and session cookies across the network
    # in clear text. Allowed - a lab, or a firewall in front - but never silently.
    [ "$(env_value CERT_ALERT_TLS_ENABLED)" = "true" ] && return 0
    local bind port
    bind=$(env_value CERT_ALERT_BIND_ADDRESS); bind=${bind:-0.0.0.0}
    port=$(env_value CERT_ALERT_PORT); port=${port:-8080}
    case "$bind" in
        0.0.0.0|::|'[::]'|'*') ;;
        *) return 0 ;;
    esac
    say "" >&2
    say "WARNING: TLS is off (CERT_ALERT_TLS_ENABLED is not true) and cert-alert is published on" >&2
    say "         every interface (CERT_ALERT_BIND_ADDRESS=$bind, port $port). Directory passwords" >&2
    say "         and session cookies will cross the network in clear text. Either:" >&2
    say "           - put a TLS-terminating reverse proxy in front and set" >&2
    say "             CERT_ALERT_BIND_ADDRESS=127.0.0.1, SERVER_FORWARD_HEADERS_STRATEGY=framework" >&2
    say "             and ENS_COOKIE_SECURE=true; or" >&2
    say "           - turn TLS on in the application (CERT_ALERT_TLS_ENABLED=true, keystores in tls/)." >&2
    say "" >&2
}

check_license() {
    if [ -f license/license.lic ]; then
        say "License: license/license.lic found; it is installed once cert-alert has started."
    else
        say "License: no license/license.lic here. If none is installed yet, upload one on the"
        say "license screen once cert-alert is up (see below); until then only sign-in and that screen work."
    fi
}

# Where the application keeps its license, inside the ens-license volume.
LICENSE_IN_VOLUME=/var/lib/ens/certalert/license.lic

install_license() {
    # Copies license/license.lic into the license volume, once the container exists. Never
    # replaces a different license already there: that may be a newer one uploaded on the
    # license screen, and an older revision put back over it is refused.
    [ -f license/license.lic ] || return 0
    if docker compose exec -T cert-alert cat "$LICENSE_IN_VOLUME" 2> /dev/null | cmp -s - license/license.lic; then
        say "License: license/license.lic is already installed."
        return 0
    fi
    if docker compose exec -T cert-alert test -s "$LICENSE_IN_VOLUME" 2> /dev/null; then
        say "License: a different license is already installed (perhaps uploaded on the license screen); kept it."
        say "         To replace it with license/license.lic, upload that file on the license screen."
        return 0
    fi
    docker compose cp license/license.lic "cert-alert:$LICENSE_IN_VOLUME" \
        || die "could not copy license/license.lic into the cert-alert container."
    # docker cp writes it as root; make it the application's, like an uploaded one.
    docker compose exec -T -u 0 cert-alert sh -c "chown 185:0 '$LICENSE_IN_VOLUME' && chmod 0664 '$LICENSE_IN_VOLUME'" \
        || say "License: could not change the copied file's owner; it is still readable."
    say "License: copied license/license.lic into the license volume ($LICENSE_IN_VOLUME)."
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
    local id user db file
    id=$(docker compose ps -q postgres 2> /dev/null || true)
    if [ -z "$id" ] || [ "$(docker inspect -f '{{.State.Running}}' "$id" 2> /dev/null)" != "true" ]; then
        say "No running database to back up."
        return 0
    fi
    user=$(env_value CERT_ALERT_DB_USER); user=${user:-certalert}
    db=$(env_value CERT_ALERT_DB_NAME); db=${db:-certalert}
    mkdir -p backups
    file="backups/${PRODUCT}-before-${VERSION}-$(date -u +%Y%m%dT%H%M%SZ).sql.gz"
    say "Backing up the database to $file ..."
    docker exec "$id" pg_dump -U "$user" -d "$db" | gzip > "$file"
    chmod 600 "$file"
}

wait_until_healthy() {
    local id status _
    id=$(docker compose ps -q cert-alert)
    say "Waiting for cert-alert to report healthy ..."
    for _ in $(seq 1 60); do
        status=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2> /dev/null || echo unknown)
        case "$status" in
            healthy|running)
                local port scheme=http
                port=$(env_value CERT_ALERT_PORT); port=${port:-8080}
                [ "$(env_value CERT_ALERT_TLS_ENABLED)" = "true" ] && scheme=https
                local url
                url="${scheme}://$(hostname -f 2> /dev/null || hostname):${port}"
                say ""
                say "cert-alert $VERSION is up: ${url}/"
                say "License screen (installation id, upload): ${url}/admin/license"
                say "Sign in as one of CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS and press Sync directory."
                return 0 ;;
            unhealthy|exited|dead) break ;;
        esac
        sleep 5
    done
    docker compose logs --tail 50 cert-alert >&2 || true
    die "cert-alert did not become healthy (status: $status). See: docker compose logs cert-alert"
}

start() {
    docker compose config --quiet || die "the compose configuration is not valid with this .env."
    docker compose up -d --remove-orphans
    install_license
    wait_until_healthy
}

check_prerequisites
say "cert-alert $VERSION - $MODE"

if [ "$MODE" = install ]; then
    make_directories
    if [ ! -f .env ]; then
        load_images
        create_env
        explain_next_steps
        exit 0
    fi
    say "Using the existing .env (not overwritten)."
    check_env
    warn_if_exposed
    check_license
    load_images
    start
else
    copy_from_previous
    [ -f .env ] || die "no .env here. Pass --from <the previous release's directory>, or copy its .env, license/ and tls/ here. For a first install run ./install.sh."
    make_directories
    newer_settings
    check_env
    warn_if_exposed
    check_license
    load_images
    backup_database
    start
    say "Images of the previous release are still on this host; 'docker image prune' removes unused ones."
fi

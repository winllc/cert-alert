#!/usr/bin/env bash
# Checks the customer release bundle's files (deploy/release) without building anything:
# runs shellcheck on its scripts and scripts/test-release-scripts.sh (install.sh, backup.sh
# and restore.sh against a stand-in engine), and its compose files rendered with every compose command
# present - docker compose, podman-compose - in each combination the bundle README documents:
#
#   default                         published on 0.0.0.0:8080
#   CERT_ALERT_BIND_ADDRESS=127.0.0.1, CERT_ALERT_PORT=9443 in .env
#                                   published on 127.0.0.1:9443
#   + docker-compose.no-ports.yml   nothing published (podman-compose 1.4.0 and later: the
#                                   scripts refuse it with an older one)
#
#   scripts/check-release-bundle.sh
#
# REQUIRE_ENGINES="docker podman" makes a missing compose command an error (as in CI);
# otherwise it is skipped with a warning. PODMAN_COMPOSE="/a/podman-compose /b/podman-compose"
# checks with each of those (CI: 1.0.6, as on RHEL 8 and 9, and the newest); by default the
# podman-compose on PATH.
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
BUNDLE="$REPO_ROOT/deploy/release"
REQUIRE_ENGINES=${REQUIRE_ENGINES:-}
read -r -a PODMAN_COMPOSE <<< "${PODMAN_COMPOSE:-podman-compose}"

fail() { printf 'check-release-bundle: %s\n' "$*" >&2; exit 1; }

echo "shellcheck"
command -v shellcheck > /dev/null || fail "shellcheck is not installed (pip install shellcheck-py)"
shellcheck -x "$BUNDLE/install.sh" "$BUNDLE/upgrade.sh" "$BUNDLE/backup.sh" "$BUNDLE/restore.sh" \
    "$BUNDLE/container-engine.sh" "$REPO_ROOT/scripts/release-bundle.sh" \
    "$REPO_ROOT/scripts/test-release-scripts.sh" "$0"

# install.sh, backup.sh and restore.sh against a stand-in container engine.
echo "release scripts"
"$REPO_ROOT/scripts/test-release-scripts.sh"

# podman-compose 1.0.x passes .env to Podman as it is, quotes included: env.template, which
# becomes .env, quotes nothing - not even in the commented-out examples.
echo "env.template"
if grep -nE "^#?[[:space:]]*[A-Z][A-Z0-9_]*=([\"']).*\1[[:space:]]*\$" "$BUNDLE/env.template"; then
    fail "env.template quotes the values above; podman-compose 1.0.x would pass the quotes on"
fi

WORK=$(mktemp -d "${TMPDIR:-/tmp}/check-release-bundle.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
# As release-bundle.sh stages it, with stand-in image names.
sed -e 's|@CERT_ALERT_IMAGE@|ghcr.io/winllc/cert-alert:0.0.0-check|' \
    -e 's|@POSTGRES_IMAGE@|docker.io/library/postgres:17.11-alpine|' \
    "$BUNDLE/docker-compose.yml" > "$WORK/docker-compose.yml"
cp "$BUNDLE/docker-compose.no-ports.yml" "$WORK/"
mkdir -p "$WORK/tls" "$WORK/email-templates"
env_default() {
    sed 's/^CERT_ALERT_DB_PASSWORD=$/CERT_ALERT_DB_PASSWORD=validate-only/' \
        "$BUNDLE/env.template" > "$WORK/.env"
}

# The cert-alert service's published ports, one host_ip:published per line.
ports_docker() {
    (cd "$WORK" && docker compose "$@" config --format json) | python3 -c '
import json, sys
for p in json.load(sys.stdin)["services"]["cert-alert"].get("ports") or []:
    print("%s:%s" % (p.get("host_ip", ""), p["published"]))'
}
ports_podman() {
    # podman-compose prints YAML; the short syntax survives as written, interpolated.
    (cd "$WORK" && "$PC" "$@" config) \
        | awk '/^  cert-alert:/{s=1; next} /^  [a-z]/{s=0} s && /^    ports:/{p=1; next}
               s && p && /^    - /{sub(/^    - /, ""); gsub(/["'"'"']/, ""); sub(/:8080$/, ""); print; next}
               {p=0}'
}

# The compose project's name, as TOOL renders it.
project_name() {
    if [ "$1" = docker ]; then
        (cd "$WORK" && docker compose config --format json) | python3 -c 'import json, sys; print(json.load(sys.stdin)["name"])'
    else
        (cd "$WORK" && "$PC" config) | sed -n 's/^name: *//p'
    fi
}

check() {
    local tool=$1 expect=$2 got
    shift 2
    got=$("ports_$tool" "$@")
    [ "$got" = "$expect" ] || fail "$tool $*: published '${got}', expected '${expect}'"
    echo "  $tool ${*:-(default)}: ${got:-nothing published}"
}

# podman-compose's version, as container-engine.sh reads it: 1.0.6 -> 1000006.
pc_version() {
    "$PC" version 2> /dev/null | sed -n 's/.*podman-composer\{0,1\} version:\{0,1\} *v\{0,1\}\([0-9][0-9.]*\).*/\1/p' \
        | head -n 1 | { IFS=. read -r major minor patch; echo $(( ${major:-0} * 1000000 + ${minor:-0} * 1000 + ${patch:-0} )); }
}

for tool in docker "${PODMAN_COMPOSE[@]/#/podman:}"; do
    PC=${tool#podman:}
    case $tool in
        docker) docker compose version > /dev/null 2>&1 && have=true || have=false ;;
        podman:*) command -v "$PC" > /dev/null 2>&1 && have=true || have=false; tool=podman ;;
    esac
    if [ "$have" = false ]; then
        case " $REQUIRE_ENGINES " in
            *" $tool "*) fail "$tool's compose command ($PC) is not installed" ;;
        esac
        echo "warning: no compose command for $tool; skipped" >&2
        continue
    fi
    reset_ok=true
    if [ "$tool" = docker ]; then
        echo "compose config with docker"
    else
        v=$(pc_version)
        [ "$v" -ge 1000006 ] || fail "$PC is podman-compose $v; the bundle supports 1.0.6 and later"
        [ "$v" -ge 1004000 ] || reset_ok=false
        echo "compose config with $("$PC" version 2> /dev/null | head -n 1)"
    fi
    env_default
    # Not --quiet, which podman-compose 1.0.x does not have; install.sh does the same.
    if [ "$tool" = docker ]; then
        (cd "$WORK" && docker compose config > /dev/null)
        (cd "$WORK" && docker compose -f docker-compose.yml -f docker-compose.no-ports.yml config > /dev/null)
    else
        (cd "$WORK" && "$PC" config > /dev/null)
        [ $reset_ok = false ] \
            || (cd "$WORK" && "$PC" -f docker-compose.yml -f docker-compose.no-ports.yml config > /dev/null)
    fi
    check "$tool" "0.0.0.0:8080"
    if [ $reset_ok = true ]; then
        check "$tool" "" -f docker-compose.yml -f docker-compose.no-ports.yml
        # The override file both compose commands read on their own, as the README has it.
        cp "$WORK/docker-compose.no-ports.yml" "$WORK/docker-compose.override.yml"
        check "$tool" ""
        rm "$WORK/docker-compose.override.yml"
    else
        echo "  podman docker-compose.no-ports.yml: needs 1.4.0 (!reset), refused by the scripts; skipped"
    fi
    # The project: name: in the compose file, and COMPOSE_PROJECT_NAME in .env over it.
    got=$(project_name "$tool")
    [ "$got" = cert-alert ] || fail "$tool: project $got, expected cert-alert"
    echo "COMPOSE_PROJECT_NAME=cert-alert-two" >> "$WORK/.env"
    got=$(project_name "$tool")
    [ "$got" = cert-alert-two ] || fail "$tool: project $got with COMPOSE_PROJECT_NAME in .env, expected cert-alert-two"
    echo "  $tool project: cert-alert, or COMPOSE_PROJECT_NAME from .env"
    sed -i '/^COMPOSE_PROJECT_NAME=/d' "$WORK/.env"
    sed -i -e 's/^CERT_ALERT_BIND_ADDRESS=.*/CERT_ALERT_BIND_ADDRESS=127.0.0.1/' \
        -e 's/^# CERT_ALERT_PORT=.*/CERT_ALERT_PORT=9443/' "$WORK/.env"
    check "$tool" "127.0.0.1:9443"
done
echo "ok"

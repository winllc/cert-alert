#!/usr/bin/env bash
# Checks the customer release bundle's files (deploy/release) without building anything:
# runs shellcheck on its scripts and scripts/test-release-scripts.sh (install.sh, backup.sh
# and restore.sh against a stand-in engine), and its compose files rendered with every compose command
# present - docker compose, podman-compose - in each combination the bundle README documents:
#
#   default                         published on 0.0.0.0:8080
#   CERT_ALERT_BIND_ADDRESS=127.0.0.1, CERT_ALERT_PORT=9443 in .env
#                                   published on 127.0.0.1:9443
#   + docker-compose.no-ports.yml   nothing published
#
#   scripts/check-release-bundle.sh
#
# REQUIRE_ENGINES="docker podman" makes a missing compose command an error (as in CI);
# otherwise it is skipped with a warning.
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
BUNDLE="$REPO_ROOT/deploy/release"
REQUIRE_ENGINES=${REQUIRE_ENGINES:-}

fail() { printf 'check-release-bundle: %s\n' "$*" >&2; exit 1; }

echo "shellcheck"
command -v shellcheck > /dev/null || fail "shellcheck is not installed (pip install shellcheck-py)"
shellcheck -x "$BUNDLE/install.sh" "$BUNDLE/upgrade.sh" "$BUNDLE/backup.sh" "$BUNDLE/restore.sh" \
    "$BUNDLE/container-engine.sh" "$REPO_ROOT/scripts/release-bundle.sh" \
    "$REPO_ROOT/scripts/test-release-scripts.sh" "$0"

# install.sh, backup.sh and restore.sh against a stand-in container engine.
echo "release scripts"
"$REPO_ROOT/scripts/test-release-scripts.sh"

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
    (cd "$WORK" && podman-compose "$@" config) \
        | awk '/^  cert-alert:/{s=1; next} /^  [a-z]/{s=0} s && /^    ports:/{p=1; next}
               s && p && /^    - /{sub(/^    - /, ""); gsub(/["'"'"']/, ""); sub(/:8080$/, ""); print; next}
               {p=0}'
}

check() {
    local tool=$1 expect=$2 got
    shift 2
    got=$("ports_$tool" "$@")
    [ "$got" = "$expect" ] || fail "$tool $*: published '${got}', expected '${expect}'"
    echo "  $tool ${*:-(default)}: ${got:-nothing published}"
}

for tool in docker podman; do
    case $tool in
        docker) docker compose version > /dev/null 2>&1 && have=true || have=false ;;
        podman) command -v podman-compose > /dev/null 2>&1 && have=true || have=false ;;
    esac
    if [ "$have" = false ]; then
        case " $REQUIRE_ENGINES " in
            *" $tool "*) fail "$tool's compose command is not installed" ;;
        esac
        echo "warning: no compose command for $tool; skipped" >&2
        continue
    fi
    echo "compose config with $tool"
    env_default
    if [ $tool = docker ]; then
        (cd "$WORK" && docker compose config --quiet)
        (cd "$WORK" && docker compose -f docker-compose.yml -f docker-compose.no-ports.yml config --quiet)
    else
        (cd "$WORK" && podman-compose config --quiet)
        (cd "$WORK" && podman-compose -f docker-compose.yml -f docker-compose.no-ports.yml config --quiet)
    fi
    check $tool "0.0.0.0:8080"
    check $tool "" -f docker-compose.yml -f docker-compose.no-ports.yml
    # The override file both compose commands read on their own, as the README has it.
    cp "$WORK/docker-compose.no-ports.yml" "$WORK/docker-compose.override.yml"
    check $tool ""
    rm "$WORK/docker-compose.override.yml"
    sed -i -e 's/^CERT_ALERT_BIND_ADDRESS=.*/CERT_ALERT_BIND_ADDRESS=127.0.0.1/' \
        -e 's/^# CERT_ALERT_PORT=.*/CERT_ALERT_PORT=9443/' "$WORK/.env"
    check $tool "127.0.0.1:9443"
done
echo "ok"

# shellcheck shell=bash
# cert-alert - which container engine, and which compose command, the bundle's scripts use.
# Sourced by install.sh (and so by upgrade.sh); not run on its own.
#
# Docker or Podman, chosen by, in this order:
#   1. --engine docker|podman on the command line (the scripts set CONTAINER_ENGINE from it);
#   2. CONTAINER_ENGINE=docker|podman in the environment;
#   3. CONTAINER_ENGINE=docker|podman in .env (install.sh writes the one it chose there, so an
#      upgrade on a host that has both keeps using the same one);
#   4. otherwise Docker if it is installed and its daemon answers, else Podman.
#
# The compose command, for Docker: `docker compose` (v2), or a standalone `docker-compose` that
# is v2. For Podman: `podman-compose` (1.0.6 or later, as EPEL has for RHEL 8 and 9) when
# installed, else `podman compose`.
#
# After select_engine: $ENGINE is docker or podman, `engine ...` runs it, `compose ...` runs
# the compose command for this project (-p, which podman-compose 1.0.x does not take from
# .env), and `service_container NAME` prints the id of a service's container.

ENGINE=""
COMPOSE_CMD=()
# podman-compose's version (1.0.6), when that is the compose command; empty otherwise.
PODMAN_COMPOSE_VERSION=""
# The compose project: COMPOSE_PROJECT_NAME from the environment, else from .env, else
# cert-alert - the order Docker Compose uses.
PROJECT=""

engine() { "$ENGINE" "$@"; }
compose() { "${COMPOSE_CMD[@]}" -p "$PROJECT" "$@"; }

_engine_die() { printf '\nerror: %s\n' "$*" >&2; exit 1; }

# The value of NAME in ./.env, without quotes; empty if unset or there is no .env.
_engine_env_value() {
    local v
    [ -f .env ] || return 0
    v=$(grep -E "^[[:space:]]*$1=" .env | tail -n 1 | cut -d= -f2- || true)
    v=${v%\"}; v=${v#\"}; v=${v%\'}; v=${v#\'}
    printf '%s' "$v"
}

# True when `docker` is Podman's Docker-compatible wrapper (the podman-docker package).
_docker_is_podman() {
    docker --version 2> /dev/null | grep -qi podman
}

# "1.0.6" as one number, 1000006, for comparing; missing or non-numeric parts count as 0.
_version_number() {
    local major minor patch
    IFS=. read -r major minor patch _ <<< "$1"
    major=${major%%[!0-9]*}; minor=${minor%%[!0-9]*}; patch=${patch%%[!0-9]*}
    echo $(( ${major:-0} * 1000000 + ${minor:-0} * 1000 + ${patch:-0} ))
}

# "1.2.3" >= "1.3.0"?
_version_at_least() {
    [ "$(_version_number "$1")" -ge "$(_version_number "$2")" ]
}

# True when the compose command is podman-compose older than VERSION.
podman_compose_before() {
    [ -n "$PODMAN_COMPOSE_VERSION" ] && ! _version_at_least "$PODMAN_COMPOSE_VERSION" "$1"
}

_select_docker_compose() {
    if docker compose version > /dev/null 2>&1; then
        COMPOSE_CMD=(docker compose)
    elif command -v docker-compose > /dev/null 2>&1; then
        local v
        v=$(docker-compose version --short 2> /dev/null || true)
        case "$v" in
            v2.*|2.*|v[3-9].*|[3-9].*) COMPOSE_CMD=(docker-compose) ;;
            *) _engine_die "docker-compose ${v:-(unknown version)} is Compose v1, which this bundle does not support; install the Docker Compose plugin (docker compose, v2)." ;;
        esac
    else
        _engine_die "Docker Compose v2 is needed: the plugin (docker compose) or a docker-compose v2 binary."
    fi
}

_select_podman_compose() {
    local v
    if command -v podman-compose > /dev/null 2>&1; then
        COMPOSE_CMD=(podman-compose)
    elif podman compose version > /dev/null 2>&1; then
        # Podman's own wrapper, which runs whichever provider containers.conf names.
        COMPOSE_CMD=(podman compose)
    else
        _engine_die "a compose command for Podman is needed: podman-compose 1.0.6 or later (your distribution's package - EPEL on RHEL - or pip install podman-compose)."
    fi
    # 1.1 and later print "podman-compose version 1.x.y", 1.0.6 "podman-compose version: 1.0.6",
    # 1.0.3 "podman-composer version  1.0.3". Nothing, when `podman compose` runs another provider.
    v=$("${COMPOSE_CMD[@]}" version 2> /dev/null \
        | sed -n 's/.*podman-composer\{0,1\} version:\{0,1\} *v\{0,1\}\([0-9][0-9.]*\).*/\1/p' | head -n 1)
    if [ -n "$v" ] && ! _version_at_least "$v" 1.0.6; then
        _engine_die "podman-compose $v is too old: 1.0.6 or later is needed."
    fi
    PODMAN_COMPOSE_VERSION=$v
    v=$(podman version --format '{{.Client.Version}}' 2> /dev/null || true)
    if [ -n "$v" ] && ! _version_at_least "$v" 4.0.0; then
        _engine_die "Podman $v is too old: 4.0 or later is needed."
    fi
}

select_engine() {
    local want=${CONTAINER_ENGINE:-}
    [ -n "$want" ] || want=$(_engine_env_value CONTAINER_ENGINE)
    case "$want" in
        ""|auto)
            if command -v docker > /dev/null 2>&1 && ! _docker_is_podman && docker info > /dev/null 2>&1; then
                want=docker
            elif command -v podman > /dev/null 2>&1; then
                want=podman
            elif command -v docker > /dev/null 2>&1; then
                _engine_die "cannot talk to the Docker daemon - is it running, and may this user use it? (Or install Podman.)"
            else
                _engine_die "neither Docker nor Podman is installed, or on PATH."
            fi ;;
        docker|podman) ;;
        *) _engine_die "CONTAINER_ENGINE=$want: use docker or podman." ;;
    esac
    ENGINE=$want
    PROJECT=${COMPOSE_PROJECT_NAME:-}
    [ -n "$PROJECT" ] || PROJECT=$(_engine_env_value COMPOSE_PROJECT_NAME)
    PROJECT=${PROJECT:-cert-alert}
    command -v "$ENGINE" > /dev/null 2>&1 || _engine_die "$ENGINE is not installed, or not on PATH."
    if [ "$ENGINE" = docker ]; then
        docker info > /dev/null 2>&1 \
            || _engine_die "cannot talk to the Docker daemon - is it running, and may this user use it?"
        _select_docker_compose
    else
        podman info > /dev/null 2>&1 || _engine_die "podman info failed - is Podman set up for this user?"
        _select_podman_compose
    fi
    printf 'Container engine: %s (%s); compose command: %s%s\n' "$ENGINE" \
        "$("$ENGINE" --version 2> /dev/null | head -n 1)" "${COMPOSE_CMD[*]}" \
        "${PODMAN_COMPOSE_VERSION:+ (podman-compose $PODMAN_COMPOSE_VERSION)}"
}

# Refuses what this compose command would misread, before it runs: on podman-compose before
# 1.4.0, an override using !reset (docker-compose.no-ports.yml), which it cannot parse; before
# 1.1.0, a quoted value in .env, which it passes to Podman as it is - quotes included.
check_compose_files() {
    if podman_compose_before 1.4.0 && [ -f docker-compose.override.yml ] \
        && grep -q '!reset' docker-compose.override.yml; then
        _engine_die "docker-compose.override.yml uses !reset (it is docker-compose.no-ports.yml), which needs podman-compose 1.4.0 or later; this is $PODMAN_COMPOSE_VERSION. Publish on this host only instead - CERT_ALERT_BIND_ADDRESS=127.0.0.1 in .env, and remove docker-compose.override.yml - or install a newer podman-compose."
    fi
    if podman_compose_before 1.1.0 && [ -f .env ]; then
        local quoted
        quoted=$(grep -E "^[[:space:]]*[A-Za-z_][A-Za-z0-9_]*=([\"']).*\1[[:space:]]*\$" .env | cut -d= -f1 | tr -d ' \t' | tr '\n' ' ' || true)
        [ -z "$quoted" ] \
            || _engine_die "quoted values in .env: ${quoted% }. podman-compose $PODMAN_COMPOSE_VERSION passes the quotes on as part of the value; remove them (everything after the = is the value, spaces included)."
    fi
    return 0
}

# Waits until the PostgreSQL of this project answers on TCP - which the server initdb runs on a
# first start does not - and prints its container's id; dies after two minutes.
wait_for_database() {
    local user=$1 db=$2 id _
    for _ in $(seq 1 60); do
        id=$(service_container postgres 2> /dev/null || true)
        if [ -n "$id" ] && engine exec "$id" pg_isready -h 127.0.0.1 -U "$user" -d "$db" > /dev/null 2>&1; then
            printf '%s\n' "$id"
            return 0
        fi
        sleep 2
    done
    _engine_die "the database did not start (${COMPOSE_CMD[*]} logs postgres)."
}

# Rootless Podman: true when this user's Podman runs without root.
engine_rootless() {
    [ "$ENGINE" = podman ] && [ "$(podman info --format '{{.Host.Security.Rootless}}' 2> /dev/null)" = true ]
}

# The id of the (first) container of compose service NAME in this project, empty if none.
# By label, which docker compose and podman-compose both set, so it needs nothing either
# compose command may lack.
service_container() {
    engine ps -a -q \
        --filter "label=com.docker.compose.project=$PROJECT" \
        --filter "label=com.docker.compose.service=$1" | head -n 1
}

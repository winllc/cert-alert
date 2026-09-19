#!/bin/bash
#
# Brings up a 389 Directory Server with the IC FSD schema, a directory to scrape, and its
# retro changelog switched on.
#
# dscontainer is 389-ds's own container entry point: it creates the instance on first run
# if there is not one under /data, and then runs it in the foreground. Everything else here
# is the first-boot loading, which is done with the server running and then handed back to
# it - so this script stays PID 1 and passes signals on rather than leaving the server to
# be killed out from under a half-finished import.
set -eu

DATA=/data
INSTANCE=localhost
SCHEMA_SOURCE=/opt/cert-alert/schema/99ic-fsd.ldif
BOOTSTRAP=/opt/cert-alert/bootstrap
MOUNTED=/bootstrap
MARKER="$DATA/.cert-alert-loaded"
GENERATED=/tmp/directory.ldif
LOADED=/tmp/all.ldif

SUFFIX="${DS_SUFFIX_NAME:-dc=example,dc=test}"
DM_PASSWORD="${DS_DM_PASSWORD:-directory-manager}"
SERVICE_PASSWORD="${LDAP_SERVICE_PASSWORD:-cert-alert}"
USERS="${LDAP_DUMMY_USERS:-100}"
SERVERS="${LDAP_DUMMY_SERVERS:-40}"

server_pid=""

# --- running the server -------------------------------------------------------------------

start_server() {
    /usr/libexec/dirsrv/dscontainer -r &
    server_pid=$!
    for _ in $(seq 1 120); do
        if ldapsearch -x -o nettimeout=2 -H ldap://127.0.0.1:3389 \
                -D "cn=Directory Manager" -w "$DM_PASSWORD" \
                -b "" -s base vendorName > /dev/null 2>&1; then
            return 0
        fi
        # A server that has died is not one to keep waiting for.
        kill -0 "$server_pid" 2>/dev/null || { echo "The directory failed to start." >&2; exit 1; }
        sleep 1
    done
    echo "The directory did not answer within two minutes." >&2
    exit 1
}

stop_server() {
    [ -n "$server_pid" ] || return 0
    kill -TERM "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
    server_pid=""
}

# Compose stops a container with SIGTERM; without this it would reach bash and not the
# server, and the directory would be killed rather than shut down.
forward_term() {
    stop_server
    exit 0
}
trap forward_term TERM INT

modify() {
    ldapmodify -x -H ldap://127.0.0.1:3389 -D "cn=Directory Manager" -w "$DM_PASSWORD" "$@"
}

start_server

# --- the schema ----------------------------------------------------------------------------
#
# After the instance exists, not before: creating one copies the system schema into
# $DATA/config/schema, and it refuses to do that into a directory that is already there.
#
# Copied on every boot rather than once, and reloaded rather than restarted into - a
# rebuilt image with a changed schema, run against a volume that already has data, should
# pick the change up.

cp "$SCHEMA_SOURCE" "$DATA/config/schema/99ic-fsd.ldif"
dsconf "$INSTANCE" -D "cn=Directory Manager" -w "$DM_PASSWORD" \
    schema reload --schemadir "$DATA/config/schema" --wait

# --- first boot ------------------------------------------------------------------------------

if [ ! -f "$MARKER" ]; then
    # The backend, which nothing has made yet: dscontainer creates an instance and writes
    # the suffix into the CLI configuration, and stops there. No backend means no database
    # to import into, and the import is what fails rather than anything saying so.
    echo "Creating the $SUFFIX backend."
    dsconf "$INSTANCE" -D "cn=Directory Manager" -w "$DM_PASSWORD" \
        backend create --suffix "$SUFFIX" --be-name userRoot

    # Anything mounted at /bootstrap wins: that is how you put a directory of your own in
    # front of the generated one.
    if [ -n "$(find "$MOUNTED" -name '*.ldif' -print -quit 2>/dev/null)" ]; then
        echo "Loading the LDIF files found in $MOUNTED."
        sorted=$(find "$MOUNTED" -name '*.ldif' | sort)
    else
        echo "Generating $USERS people and $SERVERS servers."
        generate-directory-data.sh --users "$USERS" --servers "$SERVERS" \
            --base "$SUFFIX" --out "$GENERATED"
        sorted="$GENERATED"
    fi

    # One file, imported offline. An LDAP add per entry is fine for a demo and hopeless for
    # the hundred thousand this application was built for; ldif2db writes the database
    # directly. It replaces the backend's contents, which is why the suffix entry and the
    # access control travel with the data rather than being added first.
    sed "s|@SERVICE_PASSWORD@|$SERVICE_PASSWORD|" "$BOOTSTRAP/00-base.ldif" > "$LOADED"
    for ldif in $sorted; do
        printf '\n' >> "$LOADED"
        cat "$ldif" >> "$LOADED"
    done

    echo "Importing the directory."
    stop_server
    dsctl "$INSTANCE" ldif2db userRoot "$LOADED"
    start_server

    # The retro changelog: cn=changelog, one entry per write, which is what the connector
    # follows and what this stand-in exists to provide.
    echo "Switching on the retro changelog."
    modify -f "$BOOTSTRAP/10-changelog.ldif"
    stop_server
    start_server

    # Only now does cn=changelog exist, and reading it is a grant of its own.
    modify -f "$BOOTSTRAP/20-changelog-access.ldif"

    rm -f "$LOADED" "$GENERATED"
    touch "$MARKER"
    echo "Directory ready."
else
    echo "Directory already loaded; leaving it alone."
fi

# The server is the container from here on; this only waits for it and passes on signals.
wait "$server_pid"

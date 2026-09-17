#!/bin/sh
#
# Renders the configuration, loads a directory the first time the volume is empty, and
# hands over to slapd.
set -eu

DATA_DIR=/var/lib/ldap
CONF=/etc/ldap/slapd.conf
BOOTSTRAP_DIR=/bootstrap
GENERATED=/var/lib/ldap-bootstrap/directory.ldif

LDAP_ADMIN_PASSWORD="${LDAP_ADMIN_PASSWORD:-admin}"
LDAP_SERVICE_PASSWORD="${LDAP_SERVICE_PASSWORD:-cert-alert}"
LDAP_DUMMY_USERS="${LDAP_DUMMY_USERS:-100}"
LDAP_DUMMY_SERVERS="${LDAP_DUMMY_SERVERS:-40}"

# The password is only in the configuration file, which never leaves the container, but
# hashing it costs nothing and keeps it out of `docker exec cat`.
sed "s|@ADMIN_PASSWORD_HASH@|$(slappasswd -s "$LDAP_ADMIN_PASSWORD")|" \
    /etc/ldap/slapd.conf.template > "$CONF"

if [ -f "$DATA_DIR/data.mdb" ]; then
    echo "Directory already loaded; leaving it alone."
else
    mkdir -p "$(dirname "$GENERATED")"

    # Anything mounted at /bootstrap wins: that is how you put a directory of your own in
    # front of the generated one.
    if [ -n "$(find "$BOOTSTRAP_DIR" -name '*.ldif' ! -name '00-base.ldif' -print -quit 2>/dev/null)" ]; then
        echo "Loading the LDIF files found in $BOOTSTRAP_DIR."
    else
        echo "Generating $LDAP_DUMMY_USERS people and $LDAP_DUMMY_SERVERS servers."
        generate-directory-data.sh \
            --users "$LDAP_DUMMY_USERS" \
            --servers "$LDAP_DUMMY_SERVERS" \
            --out "$GENERATED"
    fi

    # Hashed, like the admin password: it keeps the plaintext out of the database, and
    # the hash is base64, so it cannot contain anything sed would treat as syntax.
    sed "s|@SERVICE_PASSWORD@|$(slappasswd -s "$LDAP_SERVICE_PASSWORD")|" \
        "$BOOTSTRAP_DIR/00-base.ldif" > /tmp/00-base.ldif
    slapadd -q -f "$CONF" -l /tmp/00-base.ldif
    rm -f /tmp/00-base.ldif

    # Sorted, so a numbered set of mounted files loads parents before children.
    for ldif in $(find "$BOOTSTRAP_DIR" -name '*.ldif' ! -name '00-base.ldif' | sort) "$GENERATED"; do
        [ -f "$ldif" ] || continue
        echo "Loading $ldif"
        slapadd -q -f "$CONF" -l "$ldif"
    done

    chown -R openldap:openldap "$DATA_DIR"
fi

# -d keeps slapd in the foreground, which is what makes it PID 1 and lets compose stop it.
exec slapd -u openldap -g openldap -h "ldap://0.0.0.0:389/" -f "$CONF" -d "${LDAP_LOG_LEVEL:-256}"

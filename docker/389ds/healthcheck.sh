#!/bin/sh
# Healthy means the service account can bind and read the suffix, which is exactly what
# the application does first. Anything less - a server that is listening but still
# importing, say - is not something to start the application against.
exec ldapsearch -x -LLL -o nettimeout=5 \
    -H ldap://127.0.0.1:3389 \
    -D "cn=cert-alert,ou=services,${DS_SUFFIX_NAME:-dc=example,dc=test}" \
    -w "${LDAP_SERVICE_PASSWORD:-cert-alert}" \
    -b "${DS_SUFFIX_NAME:-dc=example,dc=test}" -s base dn > /dev/null

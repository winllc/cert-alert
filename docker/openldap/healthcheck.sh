#!/bin/sh
# Healthy means the service account can bind and read the suffix, which is exactly what
# the application does first.
exec ldapsearch -x -LLL -o nettimeout=5 \
    -H ldap://127.0.0.1:389 \
    -D "cn=cert-alert,ou=services,dc=example,dc=test" \
    -w "${LDAP_SERVICE_PASSWORD:-cert-alert}" \
    -b "dc=example,dc=test" -s base dn > /dev/null

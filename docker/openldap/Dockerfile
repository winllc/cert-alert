# A stand-in directory: OpenLDAP carrying the IC FSD schema, loaded with generated people
# and servers. It exists so `docker compose up` gives you something to scrape. It serves
# plaintext LDAP and stores passwords in the clear, and belongs nowhere near a network you
# care about.
FROM debian:trixie-slim

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        slapd ldap-utils openssl bash ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    # The package's own debconf-generated configuration and database are not used; this
    # image brings its own slapd.conf and loads the data itself.
    && rm -rf /etc/ldap/slapd.d /var/lib/ldap/*

# Built from the repository root, so the image and a developer's shell run the same
# generator rather than two copies that drift.
COPY docker/openldap/schema/ic-fsd.schema /etc/ldap/schema/ic-fsd.schema
COPY docker/openldap/slapd.conf.template /etc/ldap/slapd.conf.template
COPY docker/openldap/bootstrap/00-base.ldif /bootstrap/00-base.ldif
COPY docker/openldap/entrypoint.sh docker/openldap/healthcheck.sh /usr/local/bin/
COPY scripts/generate-directory-data.sh /usr/local/bin/generate-directory-data.sh

RUN chmod +x /usr/local/bin/entrypoint.sh /usr/local/bin/healthcheck.sh \
        /usr/local/bin/generate-directory-data.sh \
    && mkdir -p /var/lib/ldap /var/lib/ldap-bootstrap \
    && chown -R openldap:openldap /var/lib/ldap

# People and servers to generate on first boot, when nothing is mounted at /bootstrap.
ENV LDAP_DUMMY_USERS=100 \
    LDAP_DUMMY_SERVERS=40 \
    LDAP_ADMIN_PASSWORD=admin \
    LDAP_SERVICE_PASSWORD=cert-alert

EXPOSE 389
VOLUME ["/var/lib/ldap"]

HEALTHCHECK --interval=10s --timeout=5s --start-period=90s --retries=12 \
    CMD /usr/local/bin/healthcheck.sh || exit 1

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

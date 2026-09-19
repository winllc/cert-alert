# A stand-in directory: 389 Directory Server carrying the IC FSD schema, loaded with
# generated people and servers, with its retro changelog switched on. It exists so
# `docker compose up` gives you something to scrape - and, unlike the OpenLDAP stand-in it
# replaces, something to follow. It serves plaintext LDAP and has a default password on
# everything, and belongs nowhere near a network you care about.
FROM debian:trixie-slim

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        389-ds-base ldap-utils openssl bash ca-certificates procps \
    && rm -rf /var/lib/apt/lists/*

# The layout dscontainer insists on. It exists because 389-ds is not naturally stateless:
# everything - configuration, database, backups - goes under one volume, so resetting the
# directory means emptying /data and nothing else.
RUN mkdir -p /data/config /data/ssca \
    && ln -sf /data/config /etc/dirsrv/slapd-localhost \
    && ln -sf /data/ssca /etc/dirsrv/ssca

# Built from the repository root, so the image and a developer's shell run the same
# generator rather than two copies that drift.
COPY docker/389ds/schema/99ic-fsd.ldif /opt/cert-alert/schema/99ic-fsd.ldif
COPY docker/389ds/bootstrap/ /opt/cert-alert/bootstrap/
COPY docker/389ds/entrypoint.sh docker/389ds/healthcheck.sh /usr/local/bin/
COPY scripts/generate-directory-data.sh /usr/local/bin/generate-directory-data.sh

# CRLF from a Windows checkout would break every one of these; see .gitattributes.
RUN sed -i 's/\r$//' /usr/local/bin/entrypoint.sh /usr/local/bin/healthcheck.sh \
        /usr/local/bin/generate-directory-data.sh \
        /opt/cert-alert/schema/99ic-fsd.ldif /opt/cert-alert/bootstrap/*.ldif \
    && chmod +x /usr/local/bin/entrypoint.sh /usr/local/bin/healthcheck.sh \
        /usr/local/bin/generate-directory-data.sh

# People and servers to generate on first boot, when nothing is mounted at /bootstrap.
#
# DS_DM_PASSWORD and DS_SUFFIX_NAME are dscontainer's own: it reads them when it creates
# the instance. The rest are this image's.
ENV DS_DM_PASSWORD=directory-manager \
    DS_SUFFIX_NAME=dc=example,dc=test \
    LDAP_SERVICE_PASSWORD=cert-alert \
    LDAP_DUMMY_USERS=100 \
    LDAP_DUMMY_SERVERS=40

# 3389 rather than 389: dscontainer runs the server unprivileged, and a port below 1024
# would need a capability this does not need.
EXPOSE 3389
VOLUME ["/data"]

HEALTHCHECK --interval=10s --timeout=5s --start-period=120s --retries=18 \
    CMD /usr/local/bin/healthcheck.sh || exit 1

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

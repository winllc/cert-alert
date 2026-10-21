# cert-alert Helm chart

Installs CertAlert, which reads the certificates your LDAP directory publishes and tells the
right people before they expire. The chart runs the application (one replica) and, if you ask
for it, an evaluation-only PostgreSQL. The directory and the mail server are yours, outside
the cluster.

Full install guide: <https://winllc.github.io/ens-apps-documentation/cert-alert/install/>

## Install

The chart is published to GHCR as an OCI artifact on every release, with the same version as
the application:

```bash
helm install cert-alert oci://ghcr.io/winllc/charts/cert-alert --version X.Y.Z \
  --namespace cert-alert --create-namespace \
  -f my-values.yaml
```

It is also in each release download as `cert-alert-X.Y.Z.tgz`, covered by `SHA256SUMS` and its
signature (see the Releases page of the docs): `helm install cert-alert ./cert-alert-X.Y.Z.tgz ...`.

The chart and the image are signed with cosign (keyless, by the release workflow). Check either
by digest before installing:

```bash
cosign verify ghcr.io/winllc/charts/cert-alert@<digest> \
  --certificate-identity-regexp 'https://github.com/winllc/cert-alert/.github/workflows/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

What you must set: `ldap.url`, `ldap.base`, and either `database.host` (an external PostgreSQL)
or `database.embedded.enabled=true` (evaluation only). Set `security.adminIdentifiers` too, or
nobody can reach the license screen or run the first sync.

## The license

CertAlert needs an ENS license from the ENS licensing portal (an evaluation, or the license
issued against your order; product `certalert`). Until one is installed only sign-in and the
license screen (`/admin/license`) work.

- **Upload it** on the license screen after installing. It takes effect at once.
- **Or give it to the chart**: `--set-file license.file=./license.lic`, or a Secret of your own
  with key `license.lic` in `license.existingSecret`. An init container copies it into the
  license volume **only if no license is installed there yet**, exactly as the release bundle's
  `install.sh` does. A newer license uploaded on the license screen is never overwritten by a
  later `helm upgrade`; to replace an installed license, upload the new one on the screen.

**The license volume.** A PVC (`cert-alert-license` for a release named `cert-alert`; 100Mi, mounted at `/var/lib/ens`)
holds the license, this installation's id and the license revisions seen. The installation id
is what a bound license is tied to, so **keep this PVC**:

- `helm upgrade` keeps it.
- `helm uninstall` deletes it with the release, and a reinstall gets a new installation id (a
  bound license must then be re-bound on the portal). To keep it across uninstalls, create the
  PVC yourself and pass `persistence.license.existingClaim`.

The license screen shows the installation id; after the first sign-in it is also in
`/var/lib/ens/certalert/installation-id` in the pod.

## Values

### Image, workload, pod

| Value | Default | Description |
|---|---|---|
| `global.imageRegistry` | `""` | Mirror registry replacing the registry part of every image (app, license init, embedded PostgreSQL) |
| `image.repository` | `ghcr.io/winllc/cert-alert` | Application image |
| `image.tag` | `""` | Defaults to the chart's `appVersion` |
| `image.digest` | `""` | Pin by digest (`sha256:...`) instead of tag |
| `image.pullPolicy` | `IfNotPresent` | |
| `imagePullSecrets` | `[]` | e.g. `[{name: ghcr-pull}]` |
| `nameOverride`, `fullnameOverride` | `""` | |
| `replicaCount` | `1` | Above 1 needs a shared license volume: see [High availability](#high-availability) |
| `strategy` | `{}` | Empty: `Recreate` for one replica (the old pod releases the license volume first), `RollingUpdate` with `maxUnavailable: 0` above one |
| `minReadySeconds` | `10` | How long a new pod must stay ready to count as available |
| `podDisruptionBudget.enabled` / `.minAvailable` | `null` / `1` | `null`: on when `replicaCount` is above 1 |
| `revisionHistoryLimit` | `3` | |
| `serviceAccount.create` / `.name` / `.annotations` | `true` / `""` / `{}` | |
| `automountServiceAccountToken` | `false` | The application uses no Kubernetes API |
| `podAnnotations`, `podLabels` | `{}` | |
| `podSecurityContext` | uid/gid/fsGroup `185`, `runAsNonRoot`, `seccompProfile: RuntimeDefault` | |
| `containerSecurityContext` | no privilege escalation, drop `ALL`, `readOnlyRootFilesystem: true` | `/tmp` is an `emptyDir` |
| `openshift.enabled` | `false` | Drops `runAsUser`, `runAsGroup` and `fsGroup` so the restricted SCC assigns them |
| `resources` | requests `250m`/`768Mi`, limit `1536Mi` | The heap is 75% of the memory limit |
| `nodeSelector`, `tolerations`, `affinity`, `topologySpreadConstraints`, `priorityClassName` | empty | |
| `terminationGracePeriodSeconds` | `45` | Graceful shutdown takes up to 30s |
| `livenessProbe.*` | `/actuator/health/liveness`, every 20s | `enabled`, `initialDelaySeconds`, `periodSeconds`, `timeoutSeconds`, `failureThreshold` |
| `readinessProbe.*` | `/actuator/health/readiness`, every 10s | as above |
| `startupProbe.*` | liveness, every 10s, 60 failures (10 min) | Covers first-start migrations |

### Directory and sign-in

| Value | Default | Environment | Description |
|---|---|---|---|
| `ldap.url` | **required** | `CERT_ALERT_LDAP_URL` | e.g. `ldaps://directory.example.gov:636` |
| `ldap.base` | **required** | `CERT_ALERT_LDAP_BASE` | e.g. `dc=example,dc=gov` |
| `ldap.bindDn` | `""` | `CERT_ALERT_LDAP_USER` | Service account DN |
| `ldap.bindPassword` | `""` | `CERT_ALERT_LDAP_PASSWORD` | Or `ldap.existingSecret` + `ldap.existingSecretKey` (`ldap-password`) |
| `ldap.requireEmailPoc` | `null` | `CERT_ALERT_LDAP_REQUIRE_EMAIL_POC` | `false` for a directory holding names in `serverPOC` |
| `ldap.binaryCertificateOption` | `null` | `CERT_ALERT_LDAP_BINARY_CERTIFICATE_OPTION` | `false` for a directory with no `userCertificate;binary` |
| `ldap.pageSize` / `ldap.paged` | `null` | `CERT_ALERT_LDAP_PAGE_SIZE` / `_PAGED` | |
| `ldap.changelogEnabled` | `null` | `CERT_ALERT_LDAP_CHANGELOG_ENABLED` | Follow the directory's changelog |
| `security.adminIdentifiers` | `[]` | `CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS` | uids (or entry DNs) of administrators. An address is ignored, with a warning: see the upgrade notes below |
| `security.ldap.enabled` | `null` | `CERT_ALERT_SECURITY_LDAP_ENABLED` | The password fallback |
| `security.ldap.userSearchBase` | `""` | `CERT_ALERT_SECURITY_LDAP_USER_SEARCH_BASE` | e.g. `ou=people` |
| `security.ldap.userSearchFilter` | `""` | `CERT_ALERT_SECURITY_LDAP_USER_SEARCH_FILTER` | e.g. `(uid={0})` |
| `security.ldap.userDnPatterns` | `[]` | `CERT_ALERT_SECURITY_LDAP_USER_DN_PATTERNS` | e.g. `[uid={0},ou=people]` |
| `security.auditorIdentifiers` | `[]` | `CERT_ALERT_SECURITY_AUDITOR_IDENTIFIERS_<n>` | uids or entry DNs of read-only auditors |
| `security.groups.admin` / `.auditor` | `[]` | `CERT_ALERT_SECURITY_GROUPS_ADMIN_<n>` / `_AUDITOR_<n>` | Directory group DNs (`memberOf`) granting the role |
| `sso.publicUrl` | `""` | `CERT_ALERT_SSO_PUBLIC_URL` | Required with single sign-on: the address people reach CertAlert at; the providers' addresses are built from it, never from request headers |
| `security.groups.directory` / `.memberOfAttribute` | `null` / `""` | `CERT_ALERT_SECURITY_GROUPS_DIRECTORY` / `_MEMBER_OF_ATTRIBUTE` | Read `memberOf` at sign-in (default true, `memberOf`) |
| `sso.oidc.enabled` | `false` | `CERT_ALERT_SSO_OIDC_ENABLED` | OpenID Connect sign-in (authorization code with PKCE) |
| `sso.oidc.issuerUri` / `.clientId` | `""` | `CERT_ALERT_SSO_OIDC_ISSUER_URI` / `_CLIENT_ID` | Required when enabled. Redirect URI: `https://<host>/login/oauth2/code/oidc` |
| `sso.oidc.clientSecret` | `""` | `CERT_ALERT_SSO_OIDC_CLIENT_SECRET` | Or `sso.oidc.existingSecret` + `.existingSecretKey` (`oidc-client-secret`); empty for a public client |
| `sso.oidc.usernameClaim` / `.match` / `.groupsClaim` | `""` | `CERT_ALERT_SSO_OIDC_USERNAME_CLAIM` / `_MATCH` / `_GROUPS_CLAIM` | Default `preferred_username`, `UID` (or `DN`), none |
| `sso.oidc.adminGroups` / `.auditorGroups` | `[]` | `CERT_ALERT_SSO_OIDC_ADMIN_GROUPS_<n>` / `_AUDITOR_GROUPS_<n>` | This provider's groups granting the role; only its groups are matched |
| `sso.oidc.displayName`, `.scopes`, `.jwsAlgorithm`, `.clockSkew` | | `CERT_ALERT_SSO_OIDC_*` | |
| `sso.saml.enabled` | `false` | `CERT_ALERT_SSO_SAML_ENABLED` | SAML 2.0 sign-in. SP metadata: `https://<host>/saml2/service-provider-metadata/saml` |
| `sso.saml.metadataUrl` or `.metadataConfigMap` (+ `.metadataConfigMapKey`) | `""` | `CERT_ALERT_SSO_SAML_METADATA_LOCATION` | The identity provider's metadata, from a URL or a ConfigMap of your own |
| `sso.saml.signingSecret` | `""` | `CERT_ALERT_SSO_SAML_SIGNING_*_LOCATION` | A Secret with `tls.key` (PKCS#8 PEM) and `tls.crt` to sign requests with |
| `sso.saml.usernameAttribute` / `.match` / `.groupsAttribute` | `""` | `CERT_ALERT_SSO_SAML_*` | Default the NameID, `UID`, none |
| `sso.saml.adminGroups` / `.auditorGroups` | `[]` | `CERT_ALERT_SSO_SAML_ADMIN_GROUPS_<n>` / `_AUDITOR_GROUPS_<n>` | As for OIDC |
| `sso.saml.entityId` | `""` | `CERT_ALERT_SSO_SAML_ENTITY_ID` | An absolute URI; empty: `<publicUrl>/saml2/service-provider-metadata/saml` |
| `sso.saml.displayName`, `.clockSkew` | | `CERT_ALERT_SSO_SAML_*` | |
| `session.store` | `""` | `CERT_ALERT_SESSION_STORE` | `jdbc` (the default here): sessions in the database; `memory`: in the pod |

`null` and `""` mean "not passed": the application default applies.

### TLS and client certificates

| Value | Default | Environment | Description |
|---|---|---|---|
| `tls.enabled` | `false` | `CERT_ALERT_TLS_ENABLED` | The pod serves HTTPS on 8080, and reads client certificates from the handshake |
| `tls.keystore.existingSecret` | `""` | `CERT_ALERT_KEYSTORE` | Secret holding the server key store; required with TLS |
| `tls.keystore.key` / `.type` | `keystore.p12` / `PKCS12` | `CERT_ALERT_KEYSTORE_TYPE` | |
| `tls.keystore.password` | `""` | `CERT_ALERT_KEYSTORE_PASSWORD` | Or `passwordExistingSecret` + `passwordExistingSecretKey` (`keystore-password`) |
| `tls.truststore.existingSecret` | `""` | `CERT_ALERT_TRUSTSTORE` | The CAs whose client certificates are accepted; required with TLS |
| `tls.truststore.key` / `.type` | `truststore.p12` / `PKCS12` | `CERT_ALERT_TRUSTSTORE_TYPE` | |
| `tls.truststore.password` | `""` | `CERT_ALERT_TRUSTSTORE_PASSWORD` | Or `passwordExistingSecret` + `passwordExistingSecretKey` (`truststore-password`) |
| `caTruststore.existingSecret` | `""` | `JAVA_OPTS` | Trust store for a private-CA `ldaps://` directory, SMTP or Splunk |
| `caTruststore.key` / `.type` | `ca-truststore.p12` / `PKCS12` | | |
| `caTruststore.password` | `""` | | Or `passwordExistingSecret` + `passwordExistingSecretKey` (`ca-truststore-password`) |
| `x509.source` | `servlet` | `ENS_X509_SOURCE` | `servlet`: from the pod's TLS handshake. `header`: forwarded by a TLS-terminating ingress |
| `x509.header` | `""` (`X-SSL-Client-Cert`) | `ENS_X509_HEADER` | ingress-nginx sends `ssl-client-cert` |
| `x509.trustedProxies` | private ranges | `ENS_X509_TRUSTED_PROXIES` | Where the header is accepted from: the ingress controller's pods |
| `x509.proxySecret.existingSecret` / `.existingSecretKey` / `.value` | `""` / `proxy-secret` / `""` | `ENS_X509_PROXY_SECRET_FILE` | The secret the proxy sends in `X-ENS-Proxy-Secret`, 32+ characters. Required in header mode |
| `x509.trustedCa.existingSecret` / `.existingSecretKey` / `.value` | `""` / `ca.pem` / `""` | `ENS_X509_TRUSTED_CA_FILE` | PEM CAs a forwarded certificate must chain to. Required in header mode |
| `x509.revocation` | `""` (`ocsp-then-crl`) | `ENS_X509_REVOCATION` | `off`, `ocsp`, `crl`, `ocsp-then-crl`: checked live at sign-in. Empty: `ocsp-then-crl`, soft-fail |
| `x509.ocspResponderUrl` / `.revocationFail` / `.revocationCache` | `""` / `""` / `""` | `ENS_X509_OCSP_RESPONDER_URL` / `_REVOCATION_FAIL` / `_REVOCATION_CACHE` | `revocationFail` empty: `open` while `revocation` is empty, `closed` once it is set |
| `x509.allowedIssuers` | `""` | `ENS_X509_ALLOWED_ISSUERS` | Issuer DNs separated by `;` |
| `x509.userFilter` | `""` | `ENS_X509_USER_FILTER` | e.g. `(uid={edipi})`, with `CERT_ALERT_SECURITY_X509_REQUIRE_KNOWN_CERTIFICATE=false` in `extraEnv` |

The chart refuses to render `x509.source=header` without a proxy secret and a CA.

### Database

| Value | Default | Environment | Description |
|---|---|---|---|
| `database.host` | **required** unless embedded | `CERT_ALERT_DB_URL` | External PostgreSQL |
| `database.port` | `5432` | | |
| `database.name` | `certalert` | `CERT_ALERT_DB_NAME` | |
| `database.username` | `certalert` | `CERT_ALERT_DB_USER` | |
| `database.password` | `""` | `CERT_ALERT_DB_PASSWORD` | Or `existingSecret` + `existingSecretKey` (`db-password`). Embedded with neither: generated once, kept across upgrades |
| `database.params` | `""` | | Appended to the JDBC URL, e.g. `sslmode=verify-full` |
| `database.poolSize` | `null` | `CERT_ALERT_DB_POOL_SIZE` | |
| `database.embedded.enabled` | `false` | | **Evaluation only, no backups**: one PostgreSQL pod with a PVC |
| `database.embedded.image.*` | `docker.io/library/postgres:17.11-alpine` | | The release bundle's PostgreSQL |
| `database.embedded.persistence.*` | `8Gi`, `ReadWriteOnce` | | `size`, `storageClass`, `accessModes` |
| `database.embedded.resources`, `podSecurityContext`, `containerSecurityContext`, `nodeSelector`, `tolerations`, `affinity` | | | uid 70, read-only root filesystem |

### Email, notifications, look

| Value | Default | Environment |
|---|---|---|
| `mail.host` / `.port` / `.username` | `""` / `null` / `""` | `SPRING_MAIL_HOST` / `_PORT` / `_USERNAME` |
| `mail.password` | `""` | `SPRING_MAIL_PASSWORD` (or `existingSecret` + `existingSecretKey`, `mail-password`) |
| `mail.smtpAuth` / `mail.starttls` | `null` | `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH` / `..._STARTTLS_ENABLE` |
| `notifications.email.enabled` / `.from` / `.dryRun` | `null` / `""` / `null` | `CERT_ALERT_NOTIFICATIONS_EMAIL_ENABLED` / `_FROM`, `CERT_ALERT_EMAIL_DRY_RUN` |
| `notifications.email.templatesConfigMap` | `""` | A ConfigMap of `expiring-user.html`/`.txt`, `expiring-server.html`/`.txt`; sets `CERT_ALERT_NOTIFICATIONS_EMAIL_TEMPLATE_DIRECTORY` |
| `alerts.email.enabled` / `.from` / `.to` | `null` / `""` / `[]` | `CERT_ALERT_ALERTS_EMAIL_ENABLED` / `_FROM` / `_TO` |
| `revocation.defaultOcspUrl` / `.defaultCrlUrl` | `""` | `CERT_ALERT_DEFAULT_OCSP_URL` / `CERT_ALERT_DEFAULT_CRL_URL` |
| `banner.enabled` / `.text` / `.textColor` / `.background` / `.height` | unset | `CERT_ALERT_BANNER_*` |
| `thresholds.warningDays` / `.criticalDays` | `null` | `CERT_ALERT_WARNING_THRESHOLD_DAYS` / `CERT_ALERT_CRITICAL_THRESHOLD_DAYS` |
| `javaOpts` | `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError` | `JAVA_OPTS` |

### Audit logging (ens-audit/1)

| Value | Default | Environment |
|---|---|---|
| `audit.stdout` | `true` | `ENS_AUDIT_STDOUT` |
| `audit.file.enabled` | `false` | `ENS_AUDIT_FILE=/var/log/ens/certalert/audit.jsonl` |
| `audit.file.keep` | `30` | `ENS_AUDIT_FILE_KEEP` |
| `audit.file.persistence.*` | disabled, `1Gi` | `enabled`, `size`, `storageClass`, `accessModes`, `existingClaim`; otherwise an `emptyDir` |
| `audit.retentionDays` | `0` | `ENS_AUDIT_RETENTION_DAYS` |
| `audit.splunk.url` / `.index` / `.sourcetype` / `.insecure` | `""` / `""` / `ens:audit` / `false` | `ENS_AUDIT_SPLUNK_*` |
| `audit.splunk.token` | `""` | `ENS_AUDIT_SPLUNK_TOKEN` (or `existingSecret` + `existingSecretKey`, `splunk-hec-token`) |
| `audit.syslog.host` / `.port` / `.protocol` / `.facility` | `""` / `null` / `tcp` / `local0` | `ENS_AUDIT_SYSLOG_*` |

A release without audit logging ignores these variables.

### License and persistence

| Value | Default | Description |
|---|---|---|
| `persistence.license.enabled` | `true` | PVC at `/var/lib/ens`. `false` uses an `emptyDir`: the license and id go with the pod |
| `persistence.license.size` / `.storageClass` / `.accessModes` | `100Mi` / `""` / `[ReadWriteOnce]` | `storageClass: "-"` means `""` (no dynamic provisioning) |
| `persistence.license.existingClaim` | `""` | A PVC of your own, kept across uninstalls |
| `license.allowNewInstallationId` | `false` | Lets a new installation id be made where the application would refuse (volume not mounted, upgraded installation with no id anywhere), only while the database holds no license state; logged as an error while set |
| `persistence.license.imported` | `false` | Says the license state is already in the database (started once with one replica since upgrading, or new); required above one replica unless `readWriteMany` |
| `persistence.license.readWriteMany` | `false` | Says the license volume is ReadWriteMany, so it stays mounted above one replica (only needed to import from it while scaled up) |
| `license.existingSecret` / `.existingSecretKey` | `""` / `license.lic` | A Secret holding the license |
| `license.file` | `""` | `--set-file license.file=./license.lic` |
| `license.initImage.*` | the application image | The init container's image (it needs `sh`, `cp`, `mv`) |

### Anything else

| Value | Default | Description |
|---|---|---|
| `extraEnv` | `[]` | More environment variables; any documented setting works as its variable |
| `extraEnvFrom` | `[]` | e.g. `[{secretRef: {name: cert-alert-extra}}]` |
| `extraVolumes`, `extraVolumeMounts` | `[]` | |

### Metrics and logs

| Value | Default | Environment | Description |
|---|---|---|---|
| `metrics.enabled` | `true` | `MANAGEMENT_SERVER_PORT` | The actuator on its own port (plain HTTP), with health and `/actuator/prometheus` open there; the probes use it and the Service gets a `metrics` port. The ingress and route never route to it |
| `metrics.port` | `9090` | | |
| `metrics.serviceMonitor.enabled` | `false` | | Renders a `monitoring.coreos.com/v1` ServiceMonitor; `.interval`, `.scrapeTimeout`, `.labels` |
| `metrics.prometheusRule.enabled` | `false` | | Renders a `monitoring.coreos.com/v1` PrometheusRule with the alerts below; `.labels` (on the resource), `.alertLabels` (on every alert), `.selector`, `.jobLabel` |
| `metrics.prometheusRule.jobs` | user sweep, server sweep (26h), expiry refresh (3h) | | `CertAlertJobNotSucceeding`, one per entry: `{job, maxAgeSeconds, severity}`; `.jobFor`, `.jobSeverity` |
| `metrics.prometheusRule.digestTruncated` / `.revocationUnknown` / `.certificatesExpiring` | on | | `.enabled`, `.severity`; the last two also `.threshold` and `.for` (`6h`, `15m`) |
| `metrics.allowFrom` | `[]` | | With `networkPolicy.enabled`, the peers admitted to the metrics port |
| `logFormat` | `json` | `LOGGING_STRUCTURED_FORMAT_CONSOLE` | `json` (ECS) or `text` |

The PrometheusRule's alerts, from the business metrics (the main README's "Metrics, health
and logs"):

| Alert | Fires when |
|---|---|
| `CertAlertJobNotSucceeding` | A job in `prometheusRule.jobs` has not finished without failing for `maxAgeSeconds` - or not at all since a restart that long ago (`ens_certalert_job_last_success_seconds`, `process_start_time_seconds`). Labelled `certalert_job` |
| `CertAlertDigestTruncated` | An expiry round-up stopped at its scan limit in the last 25 hours (`ens_certalert_digest_truncated_total`) |
| `CertAlertRevocationUnknown` | More than `threshold` certificates have had no revocation answer from their CA for `for` (`ens_certalert_revocation_unknown`) |
| `CertAlertCertificatesExpiring` | More than `threshold` certificates are inside `thresholds.warningDays` (`ens_certalert_certificates{status="expiring_soon"}`) |

Series are picked by `namespace` and `service`, as the chart's ServiceMonitor labels them;
set `selector` for a scrape of your own. The metric's `job` label arrives in Prometheus as
`exported_job`, because the scrape target has a `job` of its own (`jobLabel`). The job
gauges start empty at every restart, so a job that has failed since then alerts once the
process is older than `maxAgeSeconds`.

### Networking

| Value | Default | Description |
|---|---|---|
| `service.type` | `ClusterIP` | `LoadBalancer` for TLS straight to the pod without an ingress |
| `service.port` | `8080` | |
| `service.annotations`, `loadBalancerIP`, `loadBalancerSourceRanges`, `externalTrafficPolicy`, `nodePort` | empty | |
| `ingress.enabled` / `.className` / `.annotations` / `.hosts` / `.tls` | `false` | Standard `networking.k8s.io/v1` Ingress |
| `route.enabled` / `.host` / `.annotations` | `false` | OpenShift Route |
| `route.tls.termination` | `edge` | `edge`, `passthrough` (client certificates, needs `tls.enabled`) or `reencrypt` |
| `route.tls.insecureEdgeTerminationPolicy` | `Redirect` | |
| `route.tls.destinationCACertificate` | `""` | PEM for `reencrypt` |
| `networkPolicy.enabled` | `false` | Ingress to 8080 only; egress to DNS, the database, LDAP, SMTP and the web |
| `networkPolicy.ingress.from` | `[]` | Peers allowed in; empty means any |
| `networkPolicy.egress.allowAll` | `false` | |
| `networkPolicy.egress.dns` | `true` | |
| `networkPolicy.egress.database.cidrs` | `[0.0.0.0/0]` | The embedded database is selected by label instead |
| `networkPolicy.egress.ldap` / `.smtp` / `.web` | ports and `[0.0.0.0/0]` | `web` is CRL/OCSP, the endpoint probe, Splunk HEC and syslog |
| `networkPolicy.egress.extra` | `[]` | More egress rules |
| `tests.enabled` | `true` | `helm test` pod that curls `/actuator/health/liveness` |

## Examples

The `ci/` directory holds tested examples: `default-values.yaml`, `ingress-values.yaml`,
`openshift-values.yaml`, `existing-secrets-values.yaml` and `embedded-db-values.yaml`.

### Minimal

```yaml
ldap:
  url: ldaps://directory.example.gov:636
  base: dc=example,dc=gov
  bindDn: cn=cert-alert,ou=services,dc=example,dc=gov
  bindPassword: change-me
database:
  host: postgres.example.gov
  password: change-me
security:
  adminIdentifiers: [pki-admin]
```

```bash
helm install cert-alert oci://ghcr.io/winllc/charts/cert-alert --version X.Y.Z -f minimal.yaml \
  --set-file license.file=./license.lic
kubectl port-forward svc/cert-alert 8080:8080     # then http://localhost:8080/
```

### Existing secrets

```bash
kubectl create secret generic cert-alert-secrets \
  --from-literal=db-password=... --from-literal=ldap-password=... --from-literal=mail-password=...
kubectl create secret generic cert-alert-license --from-file=license.lic=./license.lic
```

```yaml
ldap:
  url: ldaps://directory.example.gov:636
  base: dc=example,dc=gov
  bindDn: cn=cert-alert,ou=services,dc=example,dc=gov
  existingSecret: cert-alert-secrets        # key ldap-password
database:
  host: postgres.example.gov
  existingSecret: cert-alert-secrets        # key db-password
mail:
  host: smtp.example.gov
  existingSecret: cert-alert-secrets        # key mail-password
license:
  existingSecret: cert-alert-license
```

With every secret supplied this way, the chart creates no Secret of its own.

### Ingress with TLS (password sign-in)

```yaml
ingress:
  enabled: true
  className: nginx
  annotations:
    cert-manager.io/cluster-issuer: my-issuer
  hosts:
    - host: cert-alert.example.gov
      paths: [{path: /, pathType: Prefix}]
  tls:
    - secretName: cert-alert-tls
      hosts: [cert-alert.example.gov]
```

TLS ends at the ingress, so no client certificate reaches CertAlert: users sign in with their
directory password.

### X.509 client-certificate sign-in (TLS passthrough)

With `x509.source: servlet` (the default) CertAlert reads the client certificate from its own
TLS handshake, so certificate sign-in needs TLS passed through to the pod. For edge
termination at the ingress, see header mode below.

```bash
kubectl create secret generic cert-alert-tls-stores \
  --from-file=keystore.p12 --from-file=truststore.p12
kubectl create secret generic cert-alert-tls-passwords \
  --from-literal=keystore-password=... --from-literal=truststore-password=...
```

```yaml
tls:
  enabled: true
  keystore:
    existingSecret: cert-alert-tls-stores
    passwordExistingSecret: cert-alert-tls-passwords
  truststore:
    existingSecret: cert-alert-tls-stores     # the CAs whose client certificates are accepted
    passwordExistingSecret: cert-alert-tls-passwords
```

Then one of:

```yaml
# ingress-nginx, with the controller started with --enable-ssl-passthrough
ingress:
  enabled: true
  className: nginx
  annotations:
    nginx.ingress.kubernetes.io/ssl-passthrough: "true"
    nginx.ingress.kubernetes.io/backend-protocol: HTTPS
  hosts:
    - host: cert-alert.example.gov
      paths: [{path: /, pathType: Prefix}]
```

```yaml
# A LoadBalancer Service straight to the pod
service:
  type: LoadBalancer
  port: 443
  externalTrafficPolicy: Local    # keeps client addresses for the audit trail
```

```yaml
# OpenShift
route:
  enabled: true
  host: cert-alert.apps.example.gov
  tls:
    termination: passthrough
```

The key store's certificate must be issued for the name users browse to. Client
authentication is `want`, so a browser without a certificate still reaches the password form.

### X.509 client-certificate sign-in (TLS at the ingress, header mode)

The ingress controller terminates TLS, verifies the client certificate and forwards it in a
header together with a shared secret; CertAlert believes it only from `x509.trustedProxies`,
with the secret, and when it chains to `x509.trustedCa`. See `ci/header-x509-values.yaml`.

```bash
kubectl create secret generic cert-alert-x509-proxy --from-literal=proxy-secret="$(openssl rand -hex 32)"
kubectl create secret generic dod-cas --from-file=ca.pem=dod-roots-and-intermediates.pem
```

```yaml
x509:
  source: header
  header: ssl-client-cert
  trustedProxies: "10.42.0.0/16"          # the ingress controller's pods
  proxySecret: {existingSecret: cert-alert-x509-proxy}
  trustedCa: {existingSecret: dod-cas}
  revocation: ocsp-then-crl
ingress:
  enabled: true
  className: nginx
  annotations:
    nginx.ingress.kubernetes.io/auth-tls-secret: ingress-nginx/dod-cas
    nginx.ingress.kubernetes.io/auth-tls-verify-client: "optional"
    nginx.ingress.kubernetes.io/auth-tls-pass-certificate-to-upstream: "true"
```

The controller must also send `X-ENS-Proxy-Secret` with the secret's value - with
ingress-nginx, `proxy_set_header X-ENS-Proxy-Secret "<secret>";` in a configuration snippet
(where snippets are allowed) or the controller's `proxy-set-headers` ConfigMap. Use
`networkPolicy` so only the controller reaches the pod.

### OpenShift

```yaml
openshift:
  enabled: true        # the restricted-v2 SCC assigns the uid and fsGroup
route:
  enabled: true
  host: cert-alert.apps.example.gov
  tls:
    termination: edge  # or passthrough with tls.enabled, as above
```

The image runs as any uid in group 0, so it works under the restricted SCC unchanged. The
embedded database is not supported on OpenShift: use an external PostgreSQL there.

### Air-gapped

```bash
# On a connected host: the chart and the images
helm pull oci://ghcr.io/winllc/charts/cert-alert --version X.Y.Z    # or use the .tgz from the release download
docker pull ghcr.io/winllc/cert-alert:X.Y.Z
docker pull postgres:17.11-alpine                                    # only for database.embedded
# Push them to your mirror as registry.example.gov/winllc/cert-alert:X.Y.Z
# (and registry.example.gov/library/postgres:17.11-alpine), then:
helm install cert-alert ./cert-alert-X.Y.Z.tgz -f my-values.yaml \
  --set global.imageRegistry=registry.example.gov
```

The release bundle's `images/` tar holds the same images (`docker load -i`), so a site that
already has the download needs nothing from the internet.

### Embedded database (evaluation)

```yaml
database:
  embedded:
    enabled: true      # EVALUATION ONLY, NO BACKUPS
```

A random database password is generated on install and kept on upgrade. `helm uninstall` leaves
the StatefulSet's PVC (`data-cert-alert-postgresql-0` for a release named `cert-alert`) behind; delete it yourself to
start over.

### High availability

```yaml
replicaCount: 2
```

Every scheduled job takes a lock in the database, so it runs on one replica at a time, and
sessions are kept in the database, so any replica serves any request and a rolling update
signs nobody out (see the [main README](../../../README.md#high-availability)). Above one
replica the chart renders a `RollingUpdate` strategy (`maxUnavailable: 0`), a
PodDisruptionBudget (`minAvailable: 1`), a soft anti-affinity across nodes (unless `affinity`
is set) and a 5-second `preStop` pause.

**The license volume.** The license, the installation id and the revisions seen are kept in
the database, shared by every replica. The license volume is only where an installation
upgraded from a release that kept them in files has them imported from, on the first start.
So upgrade with one replica (the volume is mounted, as before), then set
`persistence.license.imported: true` and raise `replicaCount`; the chart refuses more than one
replica without it (or a ReadWriteMany volume), and the application refuses to start an
upgraded installation that has no installation id anywhere rather than make up a new one.
Above one replica the volume is not mounted - a ReadWriteOnce claim cannot follow pods across
nodes - unless it is ReadWriteMany and `persistence.license.readWriteMany: true` says so; keep
the claim anyway (scaling does not delete it). A license given with `license.file` or
`license.existingSecret` is passed as `ENS_LICENSE` and installed into the database if none
is, or if it is a newer revision.

`session.store: memory` is refused above one replica: sessions would then be each pod's own.

### Upgrade

```bash
helm upgrade cert-alert oci://ghcr.io/winllc/charts/cert-alert --version X.Y.Z -f my-values.yaml
```

With one replica the pod is replaced (`Recreate`); with more, they are rolled one at a time.
The database schema is migrated on start, and the license PVC
is kept, with the installed license and the installation id. A license given with
`license.file` or `license.existingSecret` is not copied again while one is installed. Back up
the database first: migrations are not undone by `helm rollback`.

Before upgrading, read the [upgrade notes](../../../README.md#upgrade-notes). In particular:

- `security.adminIdentifiers` now matches a uid or an entry DN only. An e-mail address there
  is ignored, and the NOTES and the startup log warn about it.
- `x509.revocation` now defaults to the application's live, soft-fail check.
- Jobs started by hand answer 202 and run in the background (`/api/v1/jobs/{id}`), and
  sessions are kept in the database: everybody signs in once more after the upgrade.
- `strategy` now defaults to `{}` (`Recreate` for one replica, as before).

### Uninstall

```bash
helm uninstall cert-alert
```

This deletes the license PVC the chart created (and with it the installation id), but not an
`existingClaim`, and not the embedded database's PVC. Back up what you need first.

## Tests

```bash
helm test cert-alert       # curls /actuator/health/liveness through the Service
```

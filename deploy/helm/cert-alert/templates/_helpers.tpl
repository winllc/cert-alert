{{/* Chart name. */}}
{{- define "cert-alert.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* Fully qualified app name, at most 63 characters. */}}
{{- define "cert-alert.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "cert-alert.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "cert-alert.labels" -}}
helm.sh/chart: {{ include "cert-alert.chart" . }}
{{ include "cert-alert.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: cert-alert
{{- end }}

{{- define "cert-alert.selectorLabels" -}}
app.kubernetes.io/name: {{ include "cert-alert.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: app
{{- end }}

{{/* The embedded database's names and labels. */}}
{{- define "cert-alert.db.fullname" -}}
{{- printf "%s-postgresql" (include "cert-alert.fullname" .) | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "cert-alert.db.selectorLabels" -}}
app.kubernetes.io/name: {{ include "cert-alert.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: database
{{- end }}

{{- define "cert-alert.db.labels" -}}
helm.sh/chart: {{ include "cert-alert.chart" . }}
{{ include "cert-alert.db.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: cert-alert
{{- end }}

{{- define "cert-alert.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "cert-alert.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/* The chart-created Secret holding inline secret values. */}}
{{- define "cert-alert.secretName" -}}
{{- printf "%s-secrets" (include "cert-alert.fullname" .) }}
{{- end }}

{{/*
An image reference. Takes (dict "image" <repository/tag/digest map> "root" $ "defaultTag" <tag>).
global.imageRegistry replaces the registry part of the repository (the first path element,
when it looks like a host: has a dot or a colon, or is localhost); a repository with no
registry part gets the mirror prefixed.
*/}}
{{- define "cert-alert.image" -}}
{{- $repo := .image.repository -}}
{{- $registry := .root.Values.global.imageRegistry -}}
{{- if $registry -}}
{{- $parts := splitList "/" $repo -}}
{{- $first := first $parts -}}
{{- if and (gt (len $parts) 1) (or (contains "." $first) (contains ":" $first) (eq "localhost" $first)) -}}
{{- $repo = printf "%s/%s" (trimSuffix "/" $registry) (join "/" (rest $parts)) -}}
{{- else -}}
{{- $repo = printf "%s/%s" (trimSuffix "/" $registry) $repo -}}
{{- end -}}
{{- end -}}
{{- if .image.digest -}}
{{- printf "%s@%s" $repo .image.digest -}}
{{- else -}}
{{- printf "%s:%s" $repo (toString (default .defaultTag .image.tag)) -}}
{{- end -}}
{{- end }}

{{- define "cert-alert.appImage" -}}
{{- include "cert-alert.image" (dict "image" .Values.image "root" . "defaultTag" .Chart.AppVersion) }}
{{- end }}

{{- define "cert-alert.licenseInitImage" -}}
{{- if .Values.license.initImage.repository }}
{{- include "cert-alert.image" (dict "image" .Values.license.initImage "root" . "defaultTag" .Chart.AppVersion) }}
{{- else }}
{{- include "cert-alert.appImage" . }}
{{- end }}
{{- end }}

{{- define "cert-alert.dbImage" -}}
{{- include "cert-alert.image" (dict "image" .Values.database.embedded.image "root" . "defaultTag" "17.11-alpine") }}
{{- end }}

{{/*
A pod or container securityContext, with runAsUser, runAsGroup and fsGroup dropped under
openshift.enabled so the restricted SCC assigns them.
*/}}
{{- define "cert-alert.securityContext" -}}
{{- $ctx := deepCopy .ctx -}}
{{- if .root.Values.openshift.enabled -}}
{{- $_ := unset $ctx "runAsUser" -}}
{{- $_ := unset $ctx "runAsGroup" -}}
{{- $_ := unset $ctx "fsGroup" -}}
{{- end -}}
{{- toYaml $ctx -}}
{{- end }}

{{/* http or https, following tls.enabled. */}}
{{- define "cert-alert.scheme" -}}
{{- if .Values.tls.enabled }}https{{ else }}http{{ end }}
{{- end }}

{{/*
Whether something in front terminates TLS and forwards the request: an ingress, unless the
pod serves TLS itself (passthrough), or an edge or reencrypt Route. Then the application
trusts X-Forwarded-* (SERVER_FORWARD_HEADERS_STRATEGY=framework), so it sees the browser's
https request. Never with passthrough: nothing in front would rewrite a client's own
X-Forwarded-For.
*/}}
{{- define "cert-alert.proxyTerminatesTls" -}}
{{- if and .Values.ingress.enabled (not .Values.tls.enabled) -}}true
{{- else if and .Values.route.enabled (has .Values.route.tls.termination (list "edge" "reencrypt")) -}}true
{{- end -}}
{{- end }}

{{/* The database host the application connects to. */}}
{{- define "cert-alert.db.host" -}}
{{- if .Values.database.embedded.enabled -}}
{{- include "cert-alert.db.fullname" . -}}
{{- else -}}
{{- required "database.host is required: the PostgreSQL server cert-alert uses (or set database.embedded.enabled=true for an evaluation-only database in the cluster)" .Values.database.host -}}
{{- end -}}
{{- end }}

{{/*
Where a secret value comes from: a dict with "name" and "key", or empty when the value is
not set at all. Takes (dict "root" $ "inline" <value> "existing" <secret name>
"existingKey" <key> "key" <key in the chart's Secret>).
*/}}
{{- define "cert-alert.secretRef" -}}
{{- if .existing -}}
{{- dict "name" .existing "key" .existingKey | toJson -}}
{{- else if .inline -}}
{{- dict "name" (include "cert-alert.secretName" .root) "key" .key | toJson -}}
{{- end -}}
{{- end }}

{{/* The database password's Secret reference (always set: generated for the embedded one). */}}
{{- define "cert-alert.db.passwordRef" -}}
{{- if .Values.database.existingSecret -}}
{{- dict "name" .Values.database.existingSecret "key" .Values.database.existingSecretKey | toJson -}}
{{- else if or .Values.database.password .Values.database.embedded.enabled -}}
{{- dict "name" (include "cert-alert.secretName" .) "key" "db-password" | toJson -}}
{{- end -}}
{{- end }}

{{/* The database password for the chart's Secret: given, kept from the last release, or new. */}}
{{- define "cert-alert.db.password" -}}
{{- if .Values.database.password -}}
{{- .Values.database.password -}}
{{- else -}}
{{- $existing := lookup "v1" "Secret" .Release.Namespace (include "cert-alert.secretName" .) -}}
{{- if and $existing $existing.data (hasKey $existing.data "db-password") -}}
{{- index $existing.data "db-password" | b64dec -}}
{{- else -}}
{{- randAlphaNum 32 -}}
{{- end -}}
{{- end -}}
{{- end }}

{{/* Whether a license is supplied to the chart. */}}
{{- define "cert-alert.licenseSupplied" -}}
{{- if or .Values.license.existingSecret .Values.license.file }}true{{ end }}
{{- end }}

{{- define "cert-alert.licenseSecretName" -}}
{{- if .Values.license.existingSecret -}}
{{- .Values.license.existingSecret -}}
{{- else -}}
{{- printf "%s-license" (include "cert-alert.fullname" .) -}}
{{- end -}}
{{- end }}

{{- define "cert-alert.licenseClaimName" -}}
{{- if .Values.persistence.license.existingClaim -}}
{{- .Values.persistence.license.existingClaim -}}
{{- else -}}
{{- printf "%s-license" (include "cert-alert.fullname" .) -}}
{{- end -}}
{{- end }}

{{- define "cert-alert.auditClaimName" -}}
{{- if .Values.audit.file.persistence.existingClaim -}}
{{- .Values.audit.file.persistence.existingClaim -}}
{{- else -}}
{{- printf "%s-audit" (include "cert-alert.fullname" .) -}}
{{- end -}}
{{- end }}

{{/* JAVA_OPTS, with the outbound trust store's options when one is given. */}}
{{- define "cert-alert.javaOpts" -}}
{{- $opts := .Values.javaOpts -}}
{{- if .Values.caTruststore.existingSecret -}}
{{- $opts = printf "%s -Djavax.net.ssl.trustStore=/etc/cert-alert/ca/%s -Djavax.net.ssl.trustStoreType=%s" $opts .Values.caTruststore.key .Values.caTruststore.type -}}
{{- if or .Values.caTruststore.password .Values.caTruststore.passwordExistingSecret -}}
{{- $opts = printf "%s -Djavax.net.ssl.trustStorePassword=$(CA_TRUSTSTORE_PASSWORD)" $opts -}}
{{- end -}}
{{- end -}}
{{- trim $opts -}}
{{- end }}

{{/* Whether the X.509 proxy secret / trusted CA come from somewhere. */}}
{{- define "cert-alert.x509ProxySecretConfigured" -}}
{{- if or .Values.x509.proxySecret.existingSecret .Values.x509.proxySecret.value }}true{{ end }}
{{- end }}

{{- define "cert-alert.x509CaConfigured" -}}
{{- if or .Values.x509.trustedCa.existingSecret .Values.x509.trustedCa.value }}true{{ end }}
{{- end }}

{{/* Validation that cannot be expressed in the schema. */}}
{{- define "cert-alert.validate" -}}
{{- if gt (int .Values.replicaCount) 1 -}}
{{- $license := .Values.persistence.license -}}
{{- if and (not (include "cert-alert.licenseVolumeMounted" .)) (not $license.imported) -}}
{{- fail "replicaCount above 1 does not mount the license volume (unless it is ReadWriteMany), so an installation upgraded from a release that kept its installation id there could not import it and would get a new one. Upgrade with replicaCount 1 first, then set persistence.license.imported=true and scale up. A new installation: set it as well." -}}
{{- end -}}
{{- if and $license.readWriteMany (not $license.existingClaim) (not (has "ReadWriteMany" $license.accessModes)) -}}
{{- fail "persistence.license.readWriteMany=true, but the chart's license volume is not ReadWriteMany: set persistence.license.accessModes to [ReadWriteMany] (a new claim - the access mode of an existing one cannot change)." -}}
{{- end -}}
{{- if eq (lower (toString .Values.session.store)) "memory" -}}
{{- fail "replicaCount above 1 needs sessions in the database: leave session.store empty (jdbc)." -}}
{{- end -}}
{{- end -}}
{{- if and (or .Values.sso.oidc.enabled .Values.sso.saml.enabled) (not (regexMatch "^https?://[^/{}?#]+(/[^{}?#]*)?$" (toString .Values.sso.publicUrl))) -}}
{{- fail "Single sign-on needs sso.publicUrl: the absolute address people reach CertAlert at, e.g. https://certalert.example.gov. The providers' addresses are built from it, never from the request's headers." -}}
{{- end -}}
{{- if and .Values.sso.saml.enabled (contains "{" (toString .Values.sso.saml.entityId)) -}}
{{- fail "sso.saml.entityId must be an absolute URI, not a template: leave it empty for <publicUrl>/saml2/service-provider-metadata/saml." -}}
{{- end -}}
{{- if and .Values.sso.oidc.enabled (not (and .Values.sso.oidc.issuerUri .Values.sso.oidc.clientId)) -}}
{{- fail "sso.oidc.enabled=true needs sso.oidc.issuerUri and sso.oidc.clientId." -}}
{{- end -}}
{{- if and .Values.sso.saml.enabled (not (or .Values.sso.saml.metadataUrl .Values.sso.saml.metadataConfigMap)) -}}
{{- fail "sso.saml.enabled=true needs sso.saml.metadataUrl or sso.saml.metadataConfigMap: the identity provider's metadata." -}}
{{- end -}}
{{- if .Values.tls.enabled -}}
{{- if not .Values.tls.keystore.existingSecret -}}
{{- fail "tls.enabled=true needs tls.keystore.existingSecret: a Secret holding the server key store (key tls.keystore.key, default keystore.p12)." -}}
{{- end -}}
{{- if not .Values.tls.truststore.existingSecret -}}
{{- fail "tls.enabled=true needs tls.truststore.existingSecret: a Secret holding the CAs whose client certificates are accepted (the application does not start without one)." -}}
{{- end -}}
{{- end -}}
{{- if .Values.route.enabled -}}
{{- $t := .Values.route.tls.termination -}}
{{- if and (has $t (list "passthrough" "reencrypt")) (not .Values.tls.enabled) -}}
{{- fail (printf "route.tls.termination=%s needs tls.enabled=true: the pod must serve HTTPS." $t) -}}
{{- end -}}
{{- if and (eq $t "edge") .Values.tls.enabled -}}
{{- fail "route.tls.termination=edge sends plain HTTP to a pod serving HTTPS (tls.enabled=true): use passthrough (client-certificate sign-in) or reencrypt." -}}
{{- end -}}
{{- end -}}
{{- if eq .Values.x509.source "header" -}}
{{- if not (include "cert-alert.x509ProxySecretConfigured" .) -}}
{{- fail "x509.source=header needs x509.proxySecret.existingSecret (or .value): the secret, at least 32 characters, that the proxy sends in X-ENS-Proxy-Secret. A forwarded certificate is believed only with it." -}}
{{- end -}}
{{- if and .Values.x509.proxySecret.value (lt (len .Values.x509.proxySecret.value) 32) -}}
{{- fail "x509.proxySecret.value must be at least 32 characters (e.g. openssl rand -hex 32)." -}}
{{- end -}}
{{- if not (include "cert-alert.x509CaConfigured" .) -}}
{{- fail "x509.source=header needs x509.trustedCa.existingSecret (or .value): the CA certificates (PEM) a forwarded certificate must chain to." -}}
{{- end -}}
{{- end -}}
{{- if and .Values.license.existingSecret .Values.license.file -}}
{{- fail "Set license.existingSecret or license.file, not both." -}}
{{- end -}}
{{- end }}

{{/*
One ConfigMap line per entry of a list, NAME_0, NAME_1, ...: what Spring binds to a list
whose entries may hold commas, as a DN does. Nothing for an empty list.
*/}}
{{- define "cert-alert.envList" -}}
{{- $name := .name -}}
{{- range $i, $value := .values }}
  {{ printf "%s_%d" $name $i }}: {{ toString $value | quote }}
{{- end }}
{{- end -}}

{{/* The strategy: as set, or Recreate for one replica and a surge-only RollingUpdate above. */}}
{{- define "cert-alert.strategy" -}}
{{- if .Values.strategy -}}
{{- toYaml .Values.strategy -}}
{{- else if gt (int .Values.replicaCount) 1 -}}
type: RollingUpdate
rollingUpdate:
  maxUnavailable: 0
  maxSurge: 1
{{- else -}}
type: Recreate
{{- end -}}
{{- end -}}

{{/*
Whether the license volume is mounted: always with one replica, where it is where an
installation upgraded from a file-based release has its id imported from on the first start;
with more, only when it is ReadWriteMany (persistence.license.readWriteMany), because the
license state is in the database and a ReadWriteOnce claim cannot follow pods across nodes.
*/}}
{{- define "cert-alert.licenseVolumeMounted" -}}
{{- $license := .Values.persistence.license -}}
{{- if and $license.enabled (or (le (int .Values.replicaCount) 1) $license.readWriteMany) }}true{{ end -}}
{{- end -}}

{{/* Whether the PodDisruptionBudget is rendered: as set, or when there is more than one replica. */}}
{{- define "cert-alert.pdbEnabled" -}}
{{- $set := .Values.podDisruptionBudget.enabled -}}
{{- if kindIs "invalid" $set -}}
{{- if gt (int .Values.replicaCount) 1 }}true{{ end -}}
{{- else if $set }}true{{ end -}}
{{- end -}}

{{/* A ConfigMap line, only when the value is set: "" and null leave the application's default. */}}
{{- define "cert-alert.envLine" -}}
{{- if not (or (kindIs "invalid" .value) (and (kindIs "string" .value) (eq .value ""))) }}
  {{ .name }}: {{ toString .value | quote }}
{{- end }}
{{- end -}}

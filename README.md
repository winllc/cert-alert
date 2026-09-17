# cert-alert

Scrapes an LDAP directory for the certificates its entries publish, caches the details
that matter, and puts them behind searchable tables.

The directory is the source of truth; this application is the index over it. It reads
people and servers out of the tree, parses every certificate they publish, stores the
parsed detail so nothing has to re-read DER or go back to LDAP to answer a question, and
alerts when a cached certificate crosses into expiry.

## Status

The framework is in place and covered by tests: the scrape, the cached certificate model,
the search tables and their filters, the scheduled jobs, the alert channels, and
authentication.

### Directory schema

Built against the **IC IdAM Full Service Directory, FSD v2021-NOV** (`DesFsdXml.pdf`). The
two object classes it defines are:

| Object class   | OID                          | What it is                          |
|----------------|------------------------------|-------------------------------------|
| `icOrgPerson`  | 2.16.840.1.101.2.2.3.73      | An IC Person, deriving from inetOrgPerson |
| `icOrgServer`  | 2.16.840.1.101.2.2.3.74      | An IC Non-Person Entity             |

The specification leaves the actual objectClass hierarchy "to the discretion of the
implementing IC Element", so every attribute name remains configurable in
`application.yml` — but the defaults are now the specification's own names, not guesses.

Two details from the spec shape the design:

- **A person has up to four addresses.** `icEmail`, `internetEmail`, `niprnetEmail` and
  `siprnetEmail` are all single-valued and all optional, on top of inetOrgPerson's `mail`.
  Each is stored in its own column; `cert-alert.ldap.user.email-precedence` decides which
  is shown as primary.
- **`serverPOC` holds a name, not an address**, and is single-valued: *"Name of an IC
  Person or IC Element organizational point of contact responsible for an IC Non-Person
  Entity"*. See [the user ↔ server join](#the-user--server-join) for how that is handled.

## Tech stack

| Concern     | Choice                                                   |
|-------------|----------------------------------------------------------|
| Language    | Java 21                                                  |
| Framework   | Spring Boot 4.1                                          |
| Build       | Gradle (Kotlin DSL) with wrapper                         |
| Directory   | Spring LDAP, paged searches                              |
| Persistence | Spring Data JPA + Flyway migrations                      |
| Database    | H2 by default, PostgreSQL for deployment                 |
| Tables      | [spring-data-jpa-datatables](https://github.com/darrachequesne/spring-data-jpa-datatables) |
| UI          | [Tabler](https://tabler.io/admin-template) + Thymeleaf + DataTables, all from webjars |
| Tests       | JUnit 5, in-memory UnboundID directory, MockMvc          |

## Running it

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

The `dev` profile runs against an **embedded sample directory** (four people, four
servers, a mix of valid, expiring and expired certificates) so the UI works with no LDAP
server to hand. Open <http://localhost:8080/users> and sign in as `alice` / `password` —
the sample gives everyone that password, and alice is the configured administrator — then
press **Sync directory**.

The sample's expiry dates are baked in and will age; regenerate it by re-enabling and
running `DevLdifGenerator`. Neither the in-memory directory nor its fixture reaches the
built jar.

```bash
./gradlew test    # run the test suite
./gradlew build   # compile, test, and produce the executable jar
java -jar build/libs/cert-alert-0.0.1-SNAPSHOT.jar
```

### As a container

```bash
docker build -t cert-alert:latest .
```

A three-stage build: the jar is built, split into layers, and reassembled into a runtime
image. The split is what makes rebuilds cheap — the dependency layer is ~78MB and changes
only when `build.gradle.kts` does, while the application layer is a couple of hundred
kilobytes and changes on every commit.

```bash
docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=postgres \
  -e CERT_ALERT_DB_URL=jdbc:postgresql://db:5432/certalert \
  -e CERT_ALERT_DB_USER=certalert \
  -e CERT_ALERT_DB_PASSWORD=... \
  -e CERT_ALERT_LDAP_URL=ldaps://directory.example.gov:636 \
  -e CERT_ALERT_LDAP_BASE=dc=example,dc=gov \
  -e CERT_ALERT_LDAP_USER='cn=cert-alert,ou=services,dc=example,dc=gov' \
  -e CERT_ALERT_LDAP_PASSWORD=... \
  cert-alert:latest
```

Or with a database alongside it:

```bash
CERT_ALERT_LDAP_URL=ldaps://directory.example.gov:636 \
CERT_ALERT_LDAP_BASE=dc=example,dc=gov \
docker compose up --build
```

Compose brings up PostgreSQL and waits for it to be healthy. It deliberately does **not**
bring up an LDAP server: this application is an index over somebody's existing directory,
and a stand-in without the IC FSD schema would prove nothing.

Notes on the image:

- It runs as **uid 1001**, not root. Nothing it does needs privilege.
- `JAVA_OPTS` defaults to `-XX:MaxRAMPercentage=75.0`, so the heap follows whatever limit
  the orchestrator sets rather than a number baked in at build time.
- The JVM is PID 1 and receives `SIGTERM` directly, which is what lets Spring shut down
  gracefully and the changelog connector stop between changes rather than mid-batch.
- **Probe `/actuator/health/liveness` and `/actuator/health/readiness`, not
  `/actuator/health`.** The aggregate turns `DOWN` when the directory is unreachable, and
  that is not a reason to restart a process still serving cached data perfectly well. The
  image's own `HEALTHCHECK` is for plain `docker run`; Kubernetes ignores it and wants its
  own probes.
- Keystores for X.509 belong in a mounted volume or a secret. `.dockerignore` excludes
  `*.p12`, `*.jks`, `*.pem` and `*.key` so one cannot be baked in by accident.
- The image cannot run the `dev` profile: its embedded sample directory is a
  `developmentOnly` dependency and is deliberately not in the jar.

Tests are not run during the image build — CI runs them, and they need an in-memory LDAP
server and a database. Build with `--build-arg RUN_TESTS=true` to run them anyway.

### Against a real directory and PostgreSQL

```bash
export CERT_ALERT_LDAP_URL=ldaps://directory.example.gov:636
export CERT_ALERT_LDAP_BASE=dc=example,dc=gov
export CERT_ALERT_LDAP_USER='cn=cert-alert,ou=services,dc=example,dc=gov'
export CERT_ALERT_LDAP_PASSWORD=...
export CERT_ALERT_DB_URL=jdbc:postgresql://localhost:5432/certalert
export CERT_ALERT_DB_USER=certalert
export CERT_ALERT_DB_PASSWORD=...
./gradlew bootRun --args='--spring.profiles.active=postgres'
```

Flyway owns the schema and migrates on startup. Hibernate is set to `validate`, so a
mapping that drifts from the migrations fails at boot rather than silently altering
tables.

## The two object types

```
DirectoryUser ──< CachedCertificate >── DirectoryServer
     email                                  serverPoc
       └──────────────── join ─────────────────┘
```

**`directory_user`** — an IC Person: `uid`, `cn`, `sn`, `givenName`, `displayName`,
`preferredName`, the four network addresses, `telephoneNumber`, `title`, `employeeType`,
`rank`, and the IC-mandatory `countryOfAffiliation`, `dutyOrganization`,
`adminOrganization`, `isICMember`, `icNetworks`, `resourceSecurityMark`.

**`directory_server`** — an IC Non-Person Entity: `cn`, `uid`, `givenName`, `description`,
`serverURL`, `icServerAddress`, `ATOStatus`, `lifeCycleStatus`, `employeeType`, the same IC
attributes as a person, and its `serverPOC` contacts.

**`cached_certificate`** — everything parsed out of a published certificate: subject,
issuer, serial, validity window, signature and key algorithm, key size, SANs, and the
SHA-256 fingerprint of the DER. A certificate belongs to exactly one owner, user or
server, enforced by a check constraint. The fingerprint is its identity, which is how a
re-sync recognises a certificate it already holds and leaves it alone.

Both object types carry a denormalised roll-up — `certificate_count`,
`certificate_status`, `earliest_expiry`, `latest_expiry` — refreshed on every sync. The
search tables sort and filter on these constantly, and keeping them on the row turns
"everyone with an expired certificate" into an indexed predicate on one table instead of
a correlated subquery. The roll-up takes the **worst** state of the entry's certificates,
so one expired certificate alongside a healthy one still reads as `EXPIRED`.

| Status          | Meaning                                              |
|-----------------|------------------------------------------------------|
| `NONE`          | Publishes no certificate (roll-up only)              |
| `VALID`         | Expires beyond the warning window                    |
| `EXPIRING_SOON` | Expires within `warning-threshold-days`              |
| `EXPIRED`       | Past its notAfter date                               |

## Signing in

Two ways in, in order of preference.

### A client certificate

Everyone in this directory already holds one, and the application already caches every
certificate the directory publishes — so a presented client certificate is matched **by the
SHA-256 fingerprint of its DER** against `cached_certificate`.

That is a stronger binding than the usual approach of pattern-matching a subject name. It
authenticates the exact bytes the directory publishes for that person, so a certificate
issued to a colliding common name does not get in, and revoking access is a matter of
removing the certificate from the directory. Only certificates belonging to an **IC Person**
authenticate; one published by an IC Non-Person Entity identifies a machine, not somebody
who should hold a session.

```yaml
server:
  ssl:
    enabled: true
    client-auth: want      # not "need" — see below
    key-store: file:/etc/cert-alert/keystore.p12
    trust-store: file:/etc/cert-alert/truststore.p12   # the CAs whose client certs are accepted
```

`client-auth: want` is deliberate. With `need`, the handshake fails for a browser holding
no certificate and the password fallback becomes unreachable. Being in the truststore only
gets a certificate as far as the application; it still has to be one the directory
publishes.

Setting `cert-alert.security.x509.require-known-certificate: false` falls back to matching
the certificate's subject and `rfc822Name` alternative names against directory entries.
That trusts a name rather than a key, so it is off by default.

### A directory password

For a browser that presents no certificate. It binds to the same directory as the person
signing in, so no password is ever stored here, and roles come from the same place either
way.

```yaml
cert-alert:
  security:
    ldap:
      user-search-base: ou=people
      user-search-filter: "(uid={0})"
      # or, where no anonymous search is permitted:
      user-dn-patterns: ["uid={0},ou=people"]
```

Set `cert-alert.security.ldap.enabled: false` for a deployment that wants certificates and
nothing else, and there will be no password path at all.

### Roles

| Role         | May                                                        |
|--------------|------------------------------------------------------------|
| `ROLE_USER`  | Read the search tables and the cached certificate detail   |
| `ROLE_ADMIN` | Also trigger the jobs and reach the management endpoints   |

Everyone who authenticates gets `ROLE_USER`. `ROLE_ADMIN` comes from
`cert-alert.security.admin-identifiers`, matched against **any** value the directory knows
a person by — an address, a uid, a common name:

```yaml
cert-alert:
  security:
    admin-identifiers: [alice@intelink.ic.gov]
```

On a fresh deployment the cache is empty, so roles for someone signing in with a password
are resolved from the directory entry they just bound against. Otherwise nobody could
trigger the first sync.

`/actuator/health` stays open so the thing can be monitored. Everything else requires
authentication; an unauthenticated API call gets a 401 rather than a login page, because a
table driven by fetch has no use for HTML in the response.

## The search tables

Two pages, `/users` and `/servers`, built on the [Tabler](https://tabler.io/admin-template)
admin template (MIT). Tabler ships as a webjar and bundles the Bootstrap 5 it is built
from, so the whole UI is served out of the application jar — no CDN, which is the point on
a network that has no route to one. DataTables renders through its Bootstrap 5 integration
so its own paging and search controls match the card they sit in.

Tabler's icon package is 6.8MB of individual SVGs, so rather than ship all of it for a
dozen glyphs, the icons used here are inlined as a sprite in
`templates/fragments/icons.html`, regenerated from the package when the set changes.

Each page carries a roll-up of the certificate states across that object type, then a card
holding the filters and the table. Expanding a row fetches that entry's cached certificate
detail on demand, along with the attributes that are too long or too rarely scanned to
earn a column — the DN among them. Those stay **searchable**: they are declared as hidden
DataTables columns rather than dropped, so the global search still reaches them.

DataTables owns paging, ordering, the global search box and the per-column search inputs in
the table footer; all of it posts as the request body and the library turns it into a JPA
query. The filters this application adds ride along as query parameters and become an
additional `Specification`, so the two never have to know about each other.

### The user ↔ server join

A server names whoever is responsible for it in `serverPOC`. The specification says that
attribute carries a **name**; directories in practice often put an **address** there
instead. Rather than pick one and be wrong half the time, every person is indexed under
every value that could name them — each of their four addresses, their `cn`, `displayName`,
`preferredName` and `uid`, all lowercased, in `directory_user_identifier`. The filter is an
indexed join against that table, so both conventions resolve.

In the UI, every user row carries a **servers →** link. It passes the person's **id**, not
their address, and the server resolves it to the full identifier set — so following the
link finds servers named either way. The free-text box next to it matches a literal
`serverPOC` value.

### Other endpoints

| Method | Path                                | Purpose                                  |
|--------|-------------------------------------|------------------------------------------|
| `POST` | `/api/v1/sync`                      | Run both sweeps now                      |
| `POST` | `/api/v1/sync/users`                | Scrape IC Persons now                    |
| `POST` | `/api/v1/sync/servers`              | Scrape IC Non-Person Entities now        |
| `POST` | `/api/v1/sync/refresh`              | Re-evaluate cached expiry                |
| `POST` | `/api/v1/sync/prune`                | Remove entries unseen past the window     |
| `GET`  | `/api/v1/sync/prune/preview`        | How many the next prune would remove      |
| `GET`  | `/api/v1/sync/runs`                 | The run log, newest first                |
| `GET`  | `/api/v1/stats/users`               | Certificate roll-up across IC Persons    |
| `GET`  | `/api/v1/stats/servers`             | Certificate roll-up across servers       |
| `GET`  | `/api/v1/users/{id}/certificates`   | Cached certificate detail for a person   |
| `GET`  | `/api/v1/servers/{id}/certificates` | Cached certificate detail for a server   |
| `GET`  | `/api/v1/servers/{id}/contacts`     | A server's points of contact             |

Errors come back as RFC 7807 problem details. Actuator is at `/actuator`
(`health`, `info`, `metrics`, `loggers`); the LDAP health indicator reports the
directory connection.

## The scheduled jobs

Four schedules, because they are four different kinds of work. All are configured under
`cert-alert.ldap` and evaluated in UTC.

| Job         | Default cron      | What it does                                             |
|-------------|-------------------|----------------------------------------------------------|
| **users**   | `0 0 2 * * *`     | Scrapes IC Persons                                        |
| **servers** | `0 0 4 * * *`     | Scrapes IC Non-Person Entities, staggered from the above  |
| **refresh** | `0 15 * * * *`    | Re-evaluates cached expiry; reads no LDAP                 |
| **prune**   | `0 0 6 * * SUN`   | Removes entries the directory has stopped publishing      |

Alongside them, the [changelog connector](#following-the-changelog) follows the directory
continuously, so the sweeps are a backstop rather than the only way a change arrives.

**Why refresh is its own job.** A certificate moves from valid to expiring to expired purely
because time passes — nothing in the directory changes. Without it, a status would only be
corrected when its entry happened to be re-scraped, so a nightly sweep would mean expiry
alerts up to a day late. It walks only certificates that could plausibly have moved (not
already expired, notAfter inside the warning window), which is a small indexed slice.

**Why prune is off by default.** Deleting directory records is not something to start doing
silently. It works on how long an entry has gone unseen rather than by diffing a sweep's
results, because a sweep that died halfway through looks exactly like a directory that lost
half its entries; with `after` set well beyond the sync interval, several consecutive
failed sweeps still cannot delete anything. Check `GET /api/v1/sync/prune/preview` before
turning it on.

Every job is guarded against overlapping itself — a sweep of a large directory can outlast
its own interval — and every run is recorded in `sync_run`, readable at
`GET /api/v1/sync/runs`.

## Following the changelog

The sweeps read the whole tree. On a directory of 100,000+ entries that is a nightly job,
not a way to find out that somebody's certificate was replaced twenty minutes ago. The
changelog connector closes that window.

```yaml
cert-alert:
  ldap:
    changelog:
      enabled: true
      base-dn: cn=changelog
      poll-interval: 10s
```

It reads `cn=changelog` — the `changeLogEntry` suffix directories derived from the
Netscape line publish (draft-good-ldap-changelog) — from where it last got to, applies each
change, and records its new position. **Off by default**: a directory that publishes no
changelog, or does not let this account read it, would leave it erroring in a loop, and the
sweeps keep the cache correct on their own.

### It re-reads rather than replaying

A `changeLogEntry` carries the modifications themselves, as an LDIF fragment. This ignores
them and re-reads the named entry instead, pushing it through the same path a sweep uses.

Replaying the fragment would mean re-implementing LDAP modify semantics — add, delete and
replace of individual attribute values, binary or not — against a format directories spell
differently. Re-reading costs one search per changed entry, which is nothing beside a sweep,
and buys three things: applying a change is **idempotent**, so one replayed after a crash is
harmless; the result **cannot drift** from what a sweep would have written; and certificate
reconciliation, the roll-ups and the alert transitions all come along for free.

| Change | What happens |
|--------|--------------|
| `add`, `modify` | Re-read the entry and upsert it |
| `delete` | Remove it from the cache |
| `modrdn` | Remove the old name, read the new one |
| anything else | Counted and stepped over |

An entry that no longer matches the sweep's search filter — its objectClass changed, say —
is removed rather than left behind as a stale row. Whether a changed DN is a person, a
server or neither is decided by running those same filters at base scope against it, so
anything a sweep would collect, the connector collects.

### Durability

Its position lives in `changelog_cursor`, so a restart resumes rather than replaying the
directory's history or skipping what happened while it was down. It advances one change at a
time and writes after each, so a crash mid-batch re-applies at most that batch.

**Gaps are the interesting case.** A changelog is trimmed as it ages. If the directory has
discarded changes the connector never reached, the cache is missing them and no amount of
further reading will find them — so that triggers a full sweep rather than carrying on
quietly wrong. It is counted in `gapsDetected` either way.

Every change number read is re-checked against the cursor rather than trusted from the
filter, because `changeNumber>=N` depends on the directory having that attribute indexed
with an integer ordering rule; where it does not, the comparison quietly becomes a string
one.

| Method | Path                        | Purpose                                    |
|--------|-----------------------------|--------------------------------------------|
| `GET`  | `/api/v1/changelog`         | Position, lag, counters and the last error  |
| `POST` | `/api/v1/changelog/poll`    | Read one batch now (admin)                  |

The page header shows a badge — *Live · change 4,182*, or how far behind it is — whenever
the connector is switched on.

**Caveats.** It runs in every instance that starts it; applying is idempotent so a second
one is harmless rather than wrong, but it is wasted work — set `auto-start: false` on all
but one, or add leader election. And the `dev` profile's embedded directory publishes no
changelog, so the connector stays off there; the tests run against the in-memory server's
real one.

## How the sync works

```
DirectoryJobScheduler  (four crons, each guarded against overlap)
        |
DirectorySyncService        streams LDAP pages, outside any transaction
        |
DirectoryPersistenceService one transaction per batch of entries
        |-- CertificateParser           DER -> cached detail + SHA-256 identity
        |-- CertificateStatusEvaluator  expiry -> status + severity
        `-- AlertDispatcher --> AlertNotifier beans (log, email, ...)
```

### Built for 100,000+ entries

| Concern | How it is handled |
|---------|-------------------|
| Directory size limits | Paged searches (`page-size`, default 500). An unpaged search silently stops at the server's limit, which here would mean quietly forgetting certificates. |
| Memory | Entries are streamed to a consumer a page at a time and never collected. Materialising a six-figure result set, each entry carrying certificates, would cost hundreds of megabytes before a row reached the database. |
| Round trips | Entries are written in batches (`batch-size`, default 200): one query to load what is already held, one flush. Per entry it would be two round trips each. |
| Insert batching | Ids come from sequences, not identity columns — Hibernate cannot batch inserts behind an identity column, since it has to round trip for each generated key. |
| Session growth | The persistence context is cleared after every batch, so it never grows to hold the directory. |
| Lazy collections | `@BatchSize` on the contact and identifier collections, so a batch costs a couple of extra queries rather than one per entry. |
| Failure isolation | A batch that fails is logged and skipped; the sweep continues and the run records the count. |

Reconciliation matches on the SHA-256 fingerprint of the DER: certificates already cached
are left alone, fingerprints the directory no longer publishes are dropped, new ones are
parsed and stored. Re-syncing an unchanged directory writes nothing.

**Alerting fires on transitions, not on discovery.** A certificate seen for the first time
is not news, it is just the first time we looked — so a first sync of a 100,000-entry
directory does not produce a flood. When a certificate already in the cache moves into
`EXPIRING_SOON` or `EXPIRED` — whether noticed by a sweep or by the refresh job — that is an
alert, carrying the owner and the contact to chase.

## Configuration

Expiry thresholds, under `cert-alert`:

| Property                  | Default | Purpose                                   |
|---------------------------|---------|-------------------------------------------|
| `warning-threshold-days`  | `30`    | Window that marks a certificate expiring soon |
| `critical-threshold-days` | `7`     | Window that escalates severity to critical    |

Scraping, under `cert-alert.ldap`:

| Property             | Default | Purpose                                        |
|----------------------|---------|------------------------------------------------|
| `page-size`          | `500`   | Entries per LDAP page                           |
| `paged`              | `true`  | Turn off only for servers without the control   |
| `batch-size`         | `200`   | Entries written per transaction                 |
| `count-limit`        | `0`     | Client-side cap on entries; 0 means none        |
| `search-timeout`     | `10m`   | Per-search time limit                           |
| `sync.enabled`       | `true`  | Set false to drive every job through the API    |
| `sync.users-cron`    | `0 0 2 * * *`  | IC Person sweep                          |
| `sync.servers-cron`  | `0 0 4 * * *`  | IC Non-Person Entity sweep               |
| `sync.refresh-cron`  | `0 15 * * * *` | Expiry re-evaluation                     |
| `prune.enabled`      | `false` | Whether stale entries are deleted at all        |
| `prune.cron`         | `0 0 6 * * SUN` | Prune schedule                          |
| `prune.after`        | `30d`   | How long an entry may go unseen before deletion |
| `changelog.enabled`  | `false` | Follow the directory's changelog continuously   |
| `changelog.auto-start` | `true` | Whether the loop starts with the application   |
| `changelog.base-dn`  | `cn=changelog` | The changelog suffix                     |
| `changelog.poll-interval` | `10s` | Wait after a poll that found nothing      |
| `changelog.batch-size` | `500` | Most changes read in one poll                  |
| `changelog.start-from` | `LATEST` | `LATEST` or `BEGINNING`, with no stored position |
| `changelog.full-sync-on-gap` | `true` | Sweep when the changelog has been trimmed past the cursor |
| `user.*`, `server.*` | FSD names | Search base, filter, and attribute names      |

Access, under `cert-alert.security`:

| Property                          | Default | Purpose                                      |
|-----------------------------------|---------|----------------------------------------------|
| `enabled`                         | `true`  | Off leaves the application open; local development only |
| `admin-identifiers`               | `[]`    | Identifiers granted `ROLE_ADMIN`              |
| `x509.enabled`                    | `true`  | Accept client certificates                    |
| `x509.require-known-certificate`  | `true`  | Match by fingerprint against the cache        |
| `ldap.enabled`                    | `true`  | Offer the password fallback                   |
| `ldap.user-search-base`           | `""`    | Where to look the username up                 |
| `ldap.user-search-filter`         | `(uid={0})` | How to look the username up               |
| `ldap.user-dn-patterns`           | `[]`    | Bind straight to a DN shape instead           |

Alert channels, under `cert-alert.alerts`:

| Property         | Default | Purpose                              |
|------------------|---------|--------------------------------------|
| `logging.enabled`| `true`  | Write alerts to the application log  |
| `email.enabled`  | `false` | Email alerts; needs `spring.mail.*`  |
| `email.to`       | `[]`    | Recipients                           |

### Adding an alert channel

Implement `AlertNotifier` and expose it as a bean. `AlertDispatcher` picks up every
implementation and isolates failures, so a broken channel cannot stop the others.

```java
@Component
class SlackAlertNotifier implements AlertNotifier {

    @Override
    public void send(CertificateAlert alert) {
        // POST alert.summary() to a webhook
    }
}
```

## Project layout

```
Dockerfile               three-stage image build
docker-compose.yml       the image with a PostgreSQL alongside it
src/main/java/com/winllc/certalert/
├── alert/       notifier SPI, dispatcher, log and email channels
├── config/      expiry thresholds, clock, DataTables repository factory
├── domain/      DirectoryUser, DirectoryServer, CachedCertificate
├── ldap/        attribute mapping, paged streaming directory client
├── repository/  DataTables repositories and the filter specifications
├── security/    X.509 and directory-password authentication, roles
├── scheduling/  the four cron jobs
├── service/     certificate parsing, status evaluation, sync
└── web/         DataTables endpoints, REST API, page controllers
src/main/resources/
├── db/migration/          Flyway migrations
├── static/{css,js}/       a thin layer over Tabler, and the table wiring
├── templates/             Thymeleaf pages, Tabler layout and the icon sprite
└── dev-directory.ldif     sample directory for the dev profile
```

## Next steps

- Group-derived roles, for directories that express administrators as a group rather than
  a list of people
- Distributed locking (ShedLock or similar) if more than one instance will run the jobs or
  the changelog connector; the overlap guard is per-process
- Certificate chain and revocation status, not just the leaf
- More alert channels (Slack, PagerDuty, webhooks)

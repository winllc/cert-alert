# cert-alert

Scrapes an LDAP directory for the certificates its entries publish, caches the details
that matter, and puts them behind searchable tables.

The directory is the source of truth; this application is the index over it. It reads
people and servers out of the tree, parses every certificate they publish, stores the
parsed detail so nothing has to re-read DER or go back to LDAP to answer a question, and
alerts when a cached certificate crosses into expiry.

## Status

The framework is in place and covered by tests: the scrape, the cached certificate model,
the search tables and their filters, and the alert channels. Authentication is **not**
implemented — see [Next steps](#next-steps).

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
| UI          | Thymeleaf + DataTables, assets served from webjars       |
| Tests       | JUnit 5, in-memory UnboundID directory, MockMvc          |

## Running it

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

The `dev` profile runs against an **embedded sample directory** (four people, four
servers, a mix of valid, expiring and expired certificates) so the UI works with no LDAP
server to hand. Open <http://localhost:8080/users> and press **Sync directory**.

The sample's expiry dates are baked in and will age; regenerate it by re-enabling and
running `DevLdifGenerator`. Neither the in-memory directory nor its fixture reaches the
built jar.

```bash
./gradlew test    # run the test suite
./gradlew build   # compile, test, and produce the executable jar
java -jar build/libs/cert-alert-0.0.1-SNAPSHOT.jar
```

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

## The search tables

Two pages, `/users` and `/servers`, each a server-side DataTables grid. DataTables owns
paging, ordering, the global search box and the per-column search inputs in the table
footer; all of it posts as the request body and the library turns it into a JPA query.
The filters this application adds ride along as query parameters and become an additional
`Specification`, so the two never have to know about each other.

| Parameter             | Applies to    | Effect                                                    |
|-----------------------|---------------|-----------------------------------------------------------|
| `expired=true`        | both          | Holds at least one expired certificate                     |
| `expired=false`       | both          | Holds certificates, none expired                           |
| `certificateStatus=`  | both          | One or more of `NONE,VALID,EXPIRING_SOON,EXPIRED`          |
| `expiringWithinDays=` | both          | Next expiry falls inside that window                       |
| `hasCertificates=`    | both          | Publishes any certificate at all                           |
| `poc=`                | servers       | Servers whose `serverPOC` holds this literal value          |
| `pocUserId=`          | servers       | Servers naming this person, by any of their identifiers     |

```bash
curl -X POST 'http://localhost:8080/api/v1/datatables/servers?pocUserId=2&expired=true' \
  -H 'Content-Type: application/json' \
  -d '{"draw":1,"start":0,"length":10,"search":{"value":"","regex":false},"order":[],
       "columns":[{"data":"commonName","searchable":true,"orderable":true,
                   "search":{"value":"","regex":false}}]}'
```

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
| `user.*`, `server.*` | FSD names | Search base, filter, and attribute names      |

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
src/main/java/com/winllc/certalert/
├── alert/       notifier SPI, dispatcher, log and email channels
├── config/      expiry thresholds, clock, DataTables repository factory
├── domain/      DirectoryUser, DirectoryServer, CachedCertificate
├── ldap/        attribute mapping, paged streaming directory client
├── repository/  DataTables repositories and the filter specifications
├── scheduling/  the four cron jobs
├── service/     certificate parsing, status evaluation, sync
└── web/         DataTables endpoints, REST API, page controllers
src/main/resources/
├── db/migration/          Flyway migrations
├── static/{css,js}/       stylesheet and table wiring
├── templates/             Thymeleaf pages
└── dev-directory.ldif     sample directory for the dev profile
```

## Next steps

- **Secure the application** — nothing is authenticated today
- Incremental sync, driven by the directory's change log rather than a full sweep
- Distributed locking (ShedLock or similar) if more than one instance will run the jobs;
  the overlap guard is per-process
- Certificate chain and revocation status, not just the leaf
- More alert channels (Slack, PagerDuty, webhooks)

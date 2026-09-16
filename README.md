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

### On the directory schema

This is built for the
[IC IdAM Full Service Directory](https://www.odni.gov/index.php/who-we-are/organizations/ic-cio/ic-technical-specifications/idam-full-service-directory)
schema. That specification was not reachable from the environment this was written in, so
**every LDAP attribute name is configuration, not code**. The defaults follow
inetOrgPerson (RFC 2798) for people and the device object class (RFC 4519) for servers,
with `serverPoc` as the point-of-contact attribute. Confirm each name against the real
schema before the first run against a live directory and correct it in
`application.yml` — a wrong attribute name yields a null column rather than an error.
The two names that matter most are `cert-alert.ldap.user.email` and
`cert-alert.ldap.server.server-poc`, because those two are the join.

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

**`directory_user`** — a person: `uid`, `cn`, `sn`, `givenName`, `displayName`, `mail`,
`telephoneNumber`, `title`, `employeeType`, `c`, `o`, `ou`.

**`directory_server`** — a server: `cn`, its FQDN, description, serial number, operating
system, `o`, `ou`, and its points of contact.

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
| `pocEmail=`           | servers       | Servers whose `serverPoc` names this address               |
| `pocUserId=`          | servers       | The same, resolving the address from a user id             |

```bash
curl -X POST 'http://localhost:8080/api/v1/datatables/servers?pocEmail=alice@example.gov&expired=true' \
  -H 'Content-Type: application/json' \
  -d '{"draw":1,"start":0,"length":10,"search":{"value":"","regex":false},"order":[],
       "columns":[{"data":"commonName","searchable":true,"orderable":true,
                   "search":{"value":"","regex":false}}]}'
```

### The user ↔ server join

A server names its points of contact by email in `serverPoc`; that address is a person's
`mail`. `serverPoc` is multi-valued, so contacts live in their own table and the filter is
an indexed join against it. Both sides are stored lowercased, so the match never depends
on how the directory happened to case either one.

In the UI, every user row carries a **servers →** link that opens the servers table
already filtered to the servers that person is responsible for.

### Other endpoints

| Method | Path                                | Purpose                                  |
|--------|-------------------------------------|------------------------------------------|
| `POST` | `/api/v1/sync`                      | Run a directory sync now                 |
| `GET`  | `/api/v1/users/{id}/certificates`   | Cached certificate detail for a user     |
| `GET`  | `/api/v1/servers/{id}/certificates` | Cached certificate detail for a server   |
| `GET`  | `/api/v1/servers/{id}/contacts`     | A server's points of contact             |

Errors come back as RFC 7807 problem details. Actuator is at `/actuator`
(`health`, `info`, `metrics`, `loggers`); the LDAP health indicator reports the
directory connection.

## How the sync works

```
DirectorySyncScheduler  (cron)
        |
DirectorySyncService        scrapes LDAP outside any transaction
        |
DirectoryPersistenceService one transaction per entry, so one bad entry
        |                   cannot roll back the whole run
        |-- CertificateParser           DER -> cached detail + SHA-256 identity
        |-- CertificateStatusEvaluator  expiry -> status + severity
        `-- AlertDispatcher --> AlertNotifier beans (log, email, ...)
```

Searches are paged (the control is stateful, so every page travels down one connection).
A directory of any size enforces a size limit, and an unpaged search just stops there —
which here would mean quietly forgetting certificates.

Reconciliation matches on fingerprint: certificates already cached are left alone,
fingerprints the directory no longer publishes are dropped, new ones are parsed and
stored. Re-syncing an unchanged directory writes nothing but the refreshed expiry state.

**Alerting fires on transitions, not on discovery.** A certificate seen for the first time
is not news, it is just the first time we looked — so a first sync of a large directory
does not produce thousands of alerts. When a certificate already in the cache moves into
`EXPIRING_SOON` or `EXPIRED`, that is an alert, carrying the owner and the contact to
chase (the user's own address, or the server's points of contact).

## Configuration

Expiry thresholds, under `cert-alert`:

| Property                  | Default | Purpose                                   |
|---------------------------|---------|-------------------------------------------|
| `warning-threshold-days`  | `30`    | Window that marks a certificate expiring soon |
| `critical-threshold-days` | `7`     | Window that escalates severity to critical    |

Scraping, under `cert-alert.ldap`:

| Property         | Default         | Purpose                                     |
|------------------|-----------------|---------------------------------------------|
| `sync-enabled`   | `true`          | Set false to drive syncs through the API     |
| `sync-cron`      | `0 0 */6 * * *` | Sync schedule, evaluated in UTC              |
| `page-size`      | `500`           | Paged search page size                       |
| `paged`          | `true`          | Turn off for servers without the control     |
| `search-timeout` | `60s`           | Per-search time limit                        |
| `user.*`         | see above       | Search base, filter, and attribute names     |
| `server.*`       | see above       | Search base, filter, and attribute names     |

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
├── ldap/        attribute mapping, paged directory client
├── repository/  DataTables repositories and the filter specifications
├── scheduling/  cron-driven sync
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
- Prune entries the directory no longer publishes (they currently linger, with a stale
  `last_synced_at`)
- Incremental sync, driven by the directory's change log rather than a full sweep
- Certificate chain and revocation status, not just the leaf
- More alert channels (Slack, PagerDuty, webhooks)

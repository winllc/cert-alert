<img src="src/main/resources/static/img/logo.png" alt="CertAlert" height="64">

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
- **Organization has two levels.** `dutyOrganization` is the agency; `dutySubOrganization`
  is the office inside it, and is optional. Both are read and both are reported on, because
  the agency-level answer on a directory this size is one row.
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

It is built on **Red Hat's Universal Base Image** — `ubi9/openjdk-21` to build and
`ubi9/openjdk-21-runtime` to run. UBI is freely redistributable and needs no subscription
to pull or to run, and it is what a RHEL or OpenShift estate already has a patching story
for, which inside an accredited network is worth more than a smaller image. Both tags are
build arguments, so a real build pins them and a move to UBI 10 is an argument rather than
an edit:

```bash
docker build \
  --build-arg UBI_JDK_IMAGE=registry.access.redhat.com/ubi9/openjdk-21:1.23-1 \
  --build-arg UBI_JRE_IMAGE=registry.access.redhat.com/ubi9/openjdk-21-runtime:1.23-1 \
  -t cert-alert:latest .
```

It runs as the image's own uid 185 rather than a user of its own making, with everything
owned by that uid and group 0 and the group given the owner's permissions — which is what
lets it start under OpenShift's default policy, where the container gets an arbitrary uid
that is only ever a member of group 0.

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

Or with a database and a directory alongside it:

```bash
docker compose up --build
```

That brings up three services — PostgreSQL, a stand-in 389 Directory Server, and the
application —
and waits for the first two to be healthy before starting the third. Sign in at
<http://localhost:8080> as `alice` / `password` and press **Sync directory**.

To point it at a real directory instead, override the connection. The stand-in keeps
running; nothing reads it.

```bash
CERT_ALERT_LDAP_URL=ldaps://directory.example.gov:636 \
CERT_ALERT_LDAP_BASE=dc=example,dc=gov \
CERT_ALERT_LDAP_USER='cn=cert-alert,ou=services,dc=example,dc=gov' \
CERT_ALERT_LDAP_PASSWORD=... \
docker compose up --build
```

#### The stand-in directory

`docker/389ds/` builds a **389 Directory Server** carrying
`docker/389ds/schema/99ic-fsd.ldif` — the IC FSD attributes and the `icOrgPerson` and
`icOrgServer` object classes, with the OIDs the specification publishes. It is a demo, and
looks like one: plaintext LDAP on port 1389, and a default password on everything.

**It publishes `cn=changelog`**, which is why it is 389-ds and not the OpenLDAP stand-in it
replaced. The retro changelog is switched on at first boot, so
[the changelog connector](#following-the-changelog) has something to follow and the whole
feature can be tried rather than described:

```bash
docker compose up --build   # then, in the application's environment:
CERT_ALERT_LDAP_CHANGELOG_ENABLED=true
```

Access control is where 389-ds differs most from what was here before. There is no
`slapd.conf`: the policy lives in the directory as `aci` attributes on the entries they
protect, and `docker/389ds/bootstrap/00-base.ldif` carries all of it — the service account
reads everything but `userPassword`, and writes exactly two things, the managed attributes
under `ou=servers` and `userCertificate`. **Reading the changelog is a grant of its own**:
`cn=changelog` is a suffix outside the data tree, so no `aci` on `dc=example,dc=test`
reaches it, and an account that can read every entry still sees an empty changelog until
`20-changelog-access.ldif` is applied. That is a real deployment's first surprise with the
connector, so the stand-in makes the grant explicitly.

The first time its volume is empty it generates a directory and imports it with `ldif2db`,
offline — an LDAP add per entry is fine for a demo and hopeless for the hundred thousand
this was built for. How much is up to you:

```bash
CERT_ALERT_DUMMY_USERS=5000 CERT_ALERT_DUMMY_SERVERS=1500 docker compose up --build
```

Or mount an LDIF of your own at `/bootstrap` and it loads that instead. `ldapsearch` works
against it from the host:

```bash
ldapsearch -x -H ldap://localhost:1389 \
  -D 'cn=cert-alert,ou=services,dc=example,dc=test' -w cert-alert \
  -b 'dc=example,dc=test' '(objectClass=icOrgServer)' cn serverPOC
```

One thing it cannot demonstrate: it serves plain LDAP, so X.509 sign-in still needs
keystores of your own.

#### Generating the data

`scripts/generate-directory-data.sh` writes the LDIF, and is what the image runs on first
boot. It needs bash and openssl and nothing else, so it is equally useful for filling a
directory of your own:

```bash
./scripts/generate-directory-data.sh --users 500 --servers 200 > directory.ldif
# -c because the LDIF carries ou=people and ou=servers, which a directory may already have
ldapadd -c -x -H ldap://localhost:1389 -D 'cn=Directory Manager' -w directory-manager -f directory.ldif
```

What it produces is deliberate rather than random: the same arguments give the same people
and servers every time, down to the addresses and who contacts whom. Only the certificates
move, because they are minted against the clock.

- **Certificates are real X.509**, minted by a throwaway CA, spread across every state the
  application reports: valid for years, inside the 30-day warning window, inside the 7-day
  critical one, expired, and absent. Each is distinct, so fingerprints identify an entry
  the way X.509 sign-in needs them to.
- **The keys and digests are mixed**: mostly RSA-2048 on SHA-256, with RSA-4096, EC P-256
  and a tail of RSA-1024 on SHA-1. A metrics page comparing key sizes across a directory
  where every certificate is identical compares nothing.
- **Points of contact are written both ways round.** `serverPOC` is a name in the
  specification and an address in most real directories, so roughly a third are names,
  most are addresses, and every ninth is an organization that matches nobody — which is
  how a server ends up with no one to tell about an expiry.
- **Contacts repeat.** They are drawn from the first fifth of the people, so filtering
  servers by one person returns several rows rather than one.
- `alice` / `password` is the administrator, matching the `dev` profile's sample.

Certificates are what make it slow: on the machine this was written on, about twenty
seconds per thousand entries against one or two without them. Pass `--no-certificates`
when what you want is volume — a hundred thousand people to watch the paged sweep work:

```bash
./scripts/generate-directory-data.sh --users 100000 --servers 20000 --no-certificates > big.ldif
```

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

### If entries sync but no certificates do

Every attribute populated, every certificate column empty, and nothing in the log that
looks like a failure. That is not an access-control problem - it is which name the
certificate was asked for by.

RFC 4522 says a certificate is transferred in its binary form, and a client insists on
that by asking for `userCertificate;binary` rather than `userCertificate`. The two are
different attribute descriptions in a search, and a request carrying the option matches
only an attribute that carries it. Directories that implement the rule return nothing for
the plain name, which is why the option is on by default.

A virtual directory is the case where that breaks down. It presents an attribute it has
assembled from some other store, under the plain name, and has no `userCertificate;binary`
to give - so the request matches nothing and comes back empty, for every entry, silently.
Radiant Logic FID 7.4 behaves this way. Turn the option off:

```bash
export CERT_ALERT_LDAP_BINARY_CERTIFICATE_OPTION=false
```

Turning it off is safe against a directory that does store the binary form: a plain
request matches an attribute whatever options it carries, so it is the qualified request
that is narrow, not this one. If you are unsure which you have, `ldapsearch` will say -
the first of these returns the certificate on a conforming directory, the second on
either:

```bash
ldapsearch -H "$CERT_ALERT_LDAP_URL" -b "$CERT_ALERT_LDAP_BASE" \
    "(uid=someone)" "userCertificate;binary"
ldapsearch -H "$CERT_ALERT_LDAP_URL" -b "$CERT_ALERT_LDAP_BASE" \
    "(uid=someone)" userCertificate
```

With the option off, how values arrive is left to the JNDI provider, which decides from
the attribute's name alone. It knows the standard ones, `userCertificate` among them. If
you have mapped the certificate to a name of your own under `cert-alert.ldap.user
.certificate` or `cert-alert.ldap.server.certificate`, declare that name binary too or it
arrives as text and will not parse:

```yaml
spring:
  ldap:
    base-environment:
      java.naming.ldap.attributes.binary: myCertificateAttribute
```

## The two object types

```
DirectoryUser ──< CachedCertificate >── DirectoryServer
     email                                  serverPoc      (from the directory)
       ├──────────────── join ─────────────────┤
       └────────── ServerContact ──────────────┘           (added here)
```

**`directory_user`** — an IC Person: `uid`, `cn`, `sn`, `givenName`, `displayName`,
`preferredName`, the four network addresses, `telephoneNumber`, `title`, `employeeType`,
`rank`, and the IC-mandatory `countryOfAffiliation`, `dutyOrganization`,
`adminOrganization`, `isICMember`, `icNetworks`, `resourceSecurityMark`.

**`directory_server`** — an IC Non-Person Entity: `cn`, `uid`, `givenName`, `description`,
`serverURL`, `icServerAddress`, `ATOStatus`, `lifeCycleStatus`, `employeeType`, the same IC
attributes as a person, and its `serverPOC` contacts.

**`server_contact`** — a point of contact added here rather than scraped: a person in the
directory, an address, or a person carrying theirs. Kept apart from `serverPOC` precisely
so a sweep, which replaces that, cannot delete it. See [Managing points of
contact](#managing-points-of-contact).

**`audit_event`** — what happened to an entry and who did it, from a contact being edited to
an alert going out. See [The audit trail](#the-audit-trail).

**`cached_certificate`** — everything parsed out of a published certificate: subject,
issuer, serial, validity window, the algorithms it uses, SANs, and the SHA-256 fingerprint
of the DER. A certificate belongs to exactly one owner, user or server, enforced by a check
constraint. The fingerprint is its identity, which is how a re-sync recognises a
certificate it already holds and leaves it alone.

Four of those columns are kept for reporting on the directory's cryptography rather than
for anything the application itself does with them:

| Column | Example | |
|--------|---------|---|
| `key_algorithm` | `RSA`, `EC`, `DSA` | what the public key is |
| `key_size` | `2048`, `384` | its size in bits: the modulus for RSA and DSA, the field size for EC — which is also what names the curve |
| `signature_algorithm` | `SHA256withRSA` | as the provider names it: two facts in one string |
| `hash_algorithm` | `SHA-256` | the digest half of that, on its own and canonically spelled |

`hash_algorithm` is stored separately because a report asking what is still signed with
SHA-1 should not have to pattern-match a name — and pattern-matching would miss RSASSA-PSS
entirely, where the algorithm name carries no digest at all and the one actually used is in
the signature parameters. It is null where the scheme has no separate digest to name, which
is the Ed25519 and Ed448 case: there the hash is part of the scheme rather than a choice
made about the certificate, and recording one would invite a report to compare it with
things that are choices.

Nothing here judges a certificate on any of this. It records what a certificate is so that
a report can.

Both object types carry a denormalised roll-up — `certificate_count`,
`certificate_status`, `earliest_expiry`, `latest_expiry` — refreshed on every sync. The
search tables sort and filter on these constantly, and keeping them on the row turns
"everyone with an expired certificate" into an indexed predicate on one table instead of
a correlated subquery.

The roll-up takes the **worst** state — but of the certificates the entry still stands on,
which is not all of them. A directory keeps what it was given: renewing publishes the new
certificate and does not withdraw the old one, so an entry that rotated exactly as it
should goes on publishing the one it replaced. An **expired** certificate is therefore
passed over while the entry still holds one that has not expired, and the status and both
expiry dates describe what is left. Otherwise every correct renewal read as `EXPIRED`
until somebody cleared the old value out, and the entries that really had lapsed were the
hardest to find among them.

Which of what is left counts then depends on the object type, because the two are not the
same shape.

**A person** needs every certificate still standing. PKI for people issues two at once — a
signing certificate and a key encipherment one — and both have to work, so the worse of
them is the state of the person. Two live certificates, one due in a week and one in a
year, read as `EXPIRING_SOON`: one half of the pair really is running out.

**A server** needs the best one. An endpoint presents one certificate, so a server
publishing a good one is a server nobody has to chase, whatever else is still lying beside
it in the directory. Rolling the worst up reported a server as expiring soon while it had a
certificate good for another year — a renewal already done, counted as work outstanding.
The two expiry dates come from that same certificate, or the row argues with itself: `VALID`
beside a column saying five days, and top of "soonest to expire" for a certificate nothing
depends on. Best means the healthiest state, and the longest-lived where two share it, so
the date answers *when does this server stop having a certificate that works*.

That is a question about what the directory publishes. What a server is **actually
serving** is a different one, which the directory cannot answer and
[the endpoint probe](#what-the-server-is-actually-serving) can.

When every certificate has expired they all count again, so an entry that has genuinely
lapsed still reads `EXPIRED`. `certificate_count` is always a count of everything
published; the details page lists the superseded ones, which have not gone away.

| Status          | Meaning                                              |
|-----------------|------------------------------------------------------|
| `NONE`          | Publishes no certificate (roll-up only)              |
| `VALID`         | Expires beyond the warning window                    |
| `EXPIRING_SOON` | Expires within `warning-threshold-days`              |
| `EXPIRED`       | Past its notAfter date                               |

### The classification banner

Two fixed bars, across the top and the bottom of every page, for the deployments that are
required to display the classification of what is on the screen — where a screenshot or a
photograph of the monitor cannot be taken without it.

```yaml
cert-alert:
  banner:
    enabled: true
    text: "UNCLASSIFIED//FOUO"
    text-color: "#ffffff"
    background: "#006400"
    height: 1.75rem
```

Off by default: an application that invents its own classification marking is worse than
one that shows none, so the text is yours to set and nothing is shown until it is.

They are **fixed**, so they stay put while a long table scrolls underneath — the point of
them is that what is on the screen is marked whatever is on the screen — and the page is
padded by `height` at each end so nothing ends up beneath one. On paper they print where
they fall, rather than being fixed to a viewport that does not exist.

**Every page carries them**, the sign-in page before anyone has signed in and the error
page after something has gone wrong included: those are still pages showing this
application's data. That is why they are `body::before` and `body::after`, drawn from the
`head` fragment, rather than elements each page includes. There is no decorator layout
here — `fragments/layout.html` is a bag of named fragments — and `head` is the one thing
all twelve pages share, but a `<head>` cannot hold body content. Drawing them in CSS puts
them on every page from one place, and leaves nothing for a page added tomorrow to forget.

Everything from configuration ends up in that stylesheet, where nothing can be escaped by
the template engine — Thymeleaf will not even evaluate a bean reference inside a `<style>`
element, which is the same instinct. So the colours and the height are checked against
what a colour and a CSS length may look like, and anything else falls back to the default.
The text cannot be checked that way, because a marking is whatever the deployment says it
is, so it is escaped instead: the quote and the backslash so it cannot end the string, and
the angle brackets as CSS code points so a `</style>` in a properties file cannot end the
element and put what follows into the page as markup.

One trade-off worth knowing: generated content is announced by screen readers but is not
selectable text, so the marking cannot be copied out of the page.

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

The logo lives in `static/img/` and ships in the jar like everything else. Two variants,
because the application has a dark bar and light pages: `logo.png` in full colour for the
sign-in page and anywhere on white, and `logo-white.png` reversed for the navigation bar,
where the navy of the full-colour one would disappear into the background. `logo-mark.png`
is the mark without the wordmark, which is what the favicons are cut from. The artifact and
the configuration prefix stay `cert-alert`; the name on the page is CertAlert.

Each page carries a roll-up of the certificate states across that object type, then a card
holding the filters and the table. Expanding a row fetches that entry's cached certificate
detail on demand, along with the attributes that are too long or too rarely scanned to
earn a column — the DN among them. Those stay **searchable**: they are declared as hidden
DataTables columns rather than dropped, so the global search still reaches them.

DataTables owns paging, ordering, the global search box and the per-column search inputs in
the table footer; all of it posts as the request body and the library turns it into a JPA
query. The filters this application adds ride along as query parameters and become an
additional `Specification`, so the two never have to know about each other.

Both tables open **soonest to expire first**, which is the order the work is in. An entry
publishing no certificate has no expiry to sort on, and where a NULL sorts is left
undefined by the standard: H2 puts them first ascending, PostgreSQL last. Left to the
database, the top of the "soonest to expire" list would be filled with entries that have
nothing to expire — on one database and not the other. So it is said explicitly, in
`application.yml`:

```yaml
spring.jpa.properties.hibernate.order_by.default_null_ordering: last
```

Hibernate then renders `nulls last` into the SQL and both databases agree. Lapsed entries
are hidden unless **Show expired** is ticked — they are what has already gone wrong rather
than what is about to — so the first row is normally the soonest one still standing.

Both tables carry the same filters, and both take the two expiry dates an entry has:

| Filter | Parameter | What it asks |
|---|---|---|
| Certificates | `certificateStatus`, `expired`, `hasCertificates` | the entry's rolled-up state — the worst of the certificates it still stands on |
| Expiring within | `expiringWithinDays` | how soon the **next** one runs out |
| Last certificate expires | `latestExpiryFrom`, `latestExpiryTo` | the day the **last** of them runs out, as a range of whole days, either end optional |
| Point of contact | `poc` | on servers, a contact whose name or address contains this; on people, somebody a `serverPOC` could name by it |
| Project | `projectId` | the entries in one project |

The two expiry dates answer different questions. "Expiring within 30 days" is the warning;
"the last certificate expires before March" is the plan — everything this team holds is gone
by then, whatever else they publish in the meantime. Both are roll-up columns on the entry
row (`earliest_expiry`, `latest_expiry`), so either is an indexed predicate rather than a
walk of the certificate history.

The point-of-contact search is a partial match, because a search box is typed into rather
than pasted into. On the servers table it looks in all three places a contact's name can
be — the directory's `serverPOC`, the address of a contact added here, and the identifiers
of the person that contact is linked to, which is what makes a name find a server whose
stored contact is an address. On the people table it asks the opposite question: who does
this `serverPOC` value mean? It matches every value the join uses, which is more than the
table's columns show.

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

**Several contacts in one value.** The attribute is multi-valued, so a server with three
contacts *can* hold three values — but a directory filled in through a form often holds
them as one value with commas between them. Read whole, that value matches nobody: it is
not an address and it is not anybody's name, so the server silently has no contacts and
nobody hears when its certificate runs out. Every value is therefore split on commas,
which leaves the ordinary single-address value untouched.

**What a value has to be.** `cert-alert.ldap.server.require-email-poc` is `true` by
default, for the directories that put addresses in this attribute: a value that is not an
address is then bad data rather than somebody's name, so it is dropped instead of being
cached as a contact nothing can be sent to, and logged with the entry's DN so it can be
corrected where it lives.

```
WARN  c.w.certalert.ldap.DirectoryEntryMapper : 'cn=db01,ou=servers,dc=example,dc=test'
      has 1 'serverPOC' value(s) that are not email addresses, ignoring them: [Bob Wilson]
```

Set it `false` for a directory that follows the specification and holds names, or one
carrying both — a name costs nothing to keep, since it only ever resolves through the
identifier index above. A whole directory of those warnings means it is the wrong way
round. The sample directory and the compose demo write `serverPOC` both ways on purpose,
to show the join landing on the same person either way, so both set it `false`.

### Addresses a person answers to

The directory publishes up to five addresses per person and all five are indexed. A
server's `serverPOC`, though, is written by whoever runs the server, and they write what
they use: an old address, a role address, or the team's distribution list. A server named
after a list has no contact at all as far as the directory is concerned.

So a person's details page carries **addresses added here** alongside the published ones.
An address added there joins the set a `serverPOC` is matched against, and the servers
named after it become theirs immediately — not at the next sweep.

A **group address** is one several people answer, which is what a distribution list is.
Each of them carries their own row for it, and each of them is a contact for the servers
named after it. The badge says how many others share it.

A sweep rebuilds each person's identifiers from the directory, so the added addresses are
handed back to it during the sweep rather than being re-added afterwards. Removing one
rebuilds the set from both sources rather than striking out the value, so removing an
address the directory also publishes leaves it in place.

### Managing points of contact

`serverPOC` is the directory's, and a sweep replaces it. That is right for a cached copy of
somebody else's data, and no use at all when the directory's answer is wrong, missing, or
not yours to change. So a server carries a second list — contacts added here, in
`server_contact`, which a sweep leaves alone.

Expand a server's row to edit them. A contact is **a person in the directory** or **an
email address**, and the two meet in the middle: an address the directory knows is linked
to whoever it belongs to on the way in, so both routes end up as the same row. What that
link buys is everything a bare string cannot — the person keeps their contact when they are
renamed, the alert follows their current address, and the **servers →** link finds this
server among the ones they are responsible for.

```
directory_server_poc      what the directory publishes, replaced by every sweep
server_contact            added here, survives a sweep
                          user_id  → a person, matched by id
                          email    → an address, matched by value
```

Both lists count as points of contact everywhere it matters: the contact filter and the
person-to-servers join match either, and an expiry alert names both. The servers table
shows the directory's value with a **+n added** badge beside it.

#### Who may edit them

**Whoever is answerable for the server**, which is a question about that server rather than
about the application:

| May manage a server's contacts | How |
|---|---|
| A point of contact for it | named in the directory's `serverPOC` by any value that names them, or added here |
| A **project administrator** of a project it belongs to | see [project administrators](#project-administrators) |
| An administrator | as with everything else |

The people who know who belongs on a contact list are the people already on it, and the
people who run the project the server is part of. Waiting on an administrator to add a
colleague is how a list goes stale, and a stale list is a certificate nobody is told about.
Nobody gets a server they have nothing to do with: the refusal is a 403 saying exactly that.

Being *in* a project is not enough. Membership is a grouping — it says what a person has to
do with a piece of work — and handing everybody in it the contact lists of every server in it
would make the grouping into an authority nobody granted.

`cert-alert.security.contact-editors: AUTHENTICATED` still opens it to anyone signed in,
where that suits the deployment. The addresses a person answers to stay with that setting
either way — those bind a person to *every* server named after one, so they are not a
per-server question.

Reading the list is open to anyone signed in, and says whether this person may edit *this*
server, so the UI shows the controls only where they will work.

### A person holds two certificates

PKI for people issues a pair, not a certificate. A signing key must never be copied and an
encryption key usually must be escrowed — somebody has to be able to read what was encrypted
to a person who has left — so they cannot be the same key, and a CA issues both in one run
minutes apart. The key usage extension is what says which is which:

| Use | Key usage bits | Who holds it |
|---|---|---|
| **Signing** | `digitalSignature`, `nonRepudiation` | a person's signing certificate |
| **Encryption** | `keyEncipherment`, `dataEncipherment`, `keyAgreement` | a person's encryption certificate |
| **Signing and encryption** | both | a server, ordinarily — a TLS key does both |
| **Other** | `keyCertSign`, `cRLSign` | a CA certificate, which is nobody's credential |

So "the most recently issued certificate" is the wrong question to ask about a person, and
asking it is how half a renewal goes unnoticed. What an entry currently holds is the newest
unexpired certificate **of each use**, which is one certificate for a server and two for a
person. The details page shows the pair together, because the failures worth seeing are only
visible together:

| Flag | What it means |
|---|---|
| **No signing certificate** | nothing current signs |
| **No encryption certificate** | nothing current can be encrypted to |
| **Pair issued apart** | the two are more than `cert-alert.credentials.pair-window` (7d) apart — one was renewed and the other was not |
| **Use not known** | cached before key usage was read; the next sweep fills it in |

That last one matters during an upgrade: the extension is read when a certificate is parsed,
so rows cached by an earlier version carry no use until a sweep re-reads them. They say so
rather than reporting both halves missing.

Anything asking "is this entry on its current certificate" — the revocation check, the
endpoint probe — means the pair when it asks it of a person.

### Risky names

A certificate's subject alternative names say what it may be used for, and that is where the
blast radius of one stolen key is decided. Each cached certificate is assessed as it is
parsed, and what is worrying about it is kept on the row:

| Flag | What it means |
|---|---|
| **Wildcard** | one key answers for every name under a domain, including hosts that do not exist yet |
| **Broad wildcard** | a wildcard over a suffix everybody shares — `*.gov`, `*.ic.gov`, a bare `*` |
| **Many names** | more than `cert-alert.risk.max-subject-alt-names` (20) |
| **Many domains** | names spanning more than `cert-alert.risk.max-domains` (3) registrable domains: what it is for is unclear |
| **Bare hostname** | a name with no domain — `web01`, `localhost` — which resolves differently on every network |

None of these is a fault in itself, which is why they are flags to look at rather than alerts
to send: a wildcard is a legitimate thing to issue, and a load balancer fronting forty
services has forty names for a reason. Both thresholds are configuration for exactly that
reason.

They show up in three places: on the certificate wherever one is rendered — the expanded row
and the details page — as badges carrying the reason; as a filter on both search tables
(`?risk=any`, or one flag by name); and counted on the metrics page, which links through to
the servers holding them.

The count of names is taken from the certificate rather than from the stored list, because
that list is truncated when it is long — by exactly the names that make a certificate worth
flagging. The assessment happens at parse time, so a sweep is what refreshes it; the
migration that added the columns backfills what was already cached from the stored names,
well enough to find the wildcards before the next sweep runs.

### What the server is actually serving

The directory records what was **issued**. Whether it was ever **installed** is known only
to the endpoint, and those two drift apart in one direction: a renewal recorded in the
directory and never deployed reads as perfectly healthy on every page here, right up to the
morning the old certificate expires.

So a server's details page has an **Endpoint** card. It opens a TLS connection — what
`openssl s_client -connect host:port -servername host` does — reads the certificate the
server presents, and compares it with the most recently issued one the directory publishes:

| Finding | What it means |
|---|---|
| **Serving the current certificate** | what came back is the newest one published |
| **Serving a superseded certificate** | the endpoint's certificate *is* published, but an older one — the renewal never got installed |
| **Certificate not published by the directory** | the directory has never held what the endpoint is serving |
| **Nothing published to compare with** | the entry publishes no certificate, so there is nothing to check against |
| **Expired** / **Not valid yet** | the presented certificate's own dates |
| **Name does not match** | the host asked for is not one the certificate is good for |
| **Self-signed** | issuer and subject are the same |
| **No intermediate certificates sent** | the leaf and nothing else, which builds a path on a machine that already holds the intermediate and fails on one that does not |

```
POST /api/v1/servers/{id}/probe?port=8443
```

**The port is the only thing the caller chooses.** The host comes from the entry —
`serverURL` first, since a URL is the one attribute that says what a server answers to by
name (and its port, where it names one), then `icServerAddress`. A caller choosing the host
would be choosing which machine this application connects to, which is a different thing
from choosing between the ports of a machine the directory already names.

The handshake deliberately trusts every chain, because an expired or self-signed
certificate is exactly what is worth reporting and a validating trust manager would hang up
before it could be read. Nothing is sent over the connection and nothing read from it is
believed: the certificate is judged against the directory, which is the only thing here that
is trusted.

Nothing is stored — the answer is true at the moment it is asked and stops being true the
next time somebody restarts a service. The audit trail records that it was asked and by
whom. Who may ask is the same question as who may manage the server's contacts: a point of
contact, an administrator of a project it belongs to, or an administrator. Set
`cert-alert.probe.enabled: false` to remove it entirely on a network where reaching a
server from here is not something this application should be doing.

### Revocation

Everything else here reads a date off a certificate. Revocation is the one fact about a
certificate that the certificate does not carry: it is a decision made by the issuing
authority, published somewhere else, and nothing about the certificate changes when it
happens. A key reported stolen in March is still valid until 2028 as far as every other
column in the cache is concerned.

So there is a job of its own, after both sweeps:

```yaml
cert-alert:
  revocation:
    enabled: true
    cron: "0 30 5 * * *"
    issuer-directory: /etc/cert-alert/issuers
```

It asks about **the certificates an entry currently holds** — the pair, for a person. An
authority revoking one that has already been replaced is not news, and asking about every
superseded certificate a directory has ever held would multiply the work by however long it
has been running.

**CRL is what the scheduled job uses wherever there is a list to use**, because of how the
two mechanisms scale. A CRL is one download that answers for every certificate an authority
ever issued, and a directory of a hundred thousand entries is issued by a handful of
authorities — so it is a handful of
downloads, held for as long as each list says it is good for (`nextUpdate`, capped by
`crl-cache-ttl`). OCSP is one request per certificate; it gives a fresher answer about one
certificate, which is the right trade when the question is about one certificate and the
wrong one when it is about two hundred thousand. Distribution points are tried in the order
the certificate lists them, and `ldap://` ones work — which is how a PKI with no route to
the internet publishes its lists.

**Where there is no list, the job asks a responder instead.** That costs more, and it costs
something else besides: an OCSP request names the certificate being asked about, and the
cache holds a certificate's details rather than its bytes — so the entry has to be read back
out of the directory to form the question. One read per entry, and only for the entries
whose certificates can be answered no other way; a deployment whose certificates name their
distribution points reads the directory no more than it did before.

| Status | What it means |
|---|---|
| **Revoked** | the authority lists this serial. Whatever the dates say, it should not be in use |
| **Not revoked** | the authority was asked and does not list it |
| **Unknown** | asked, and no answer: nothing at the distribution point, a list that would not parse, or a certificate that names nowhere to ask |
| **Not checked** | never asked |

**Nothing here ever reports GOOD because it failed to find out.** An unanswered question is
not a negative answer, and recording it as one is how a revoked certificate stays in
service. That is also why *Not checked* is shown on every certificate rather than hidden: an
absent row reads like a clean bill of health.

`issuer-directory` is a directory of CA certificates, PEM or DER. Two things need them: a
CRL is signed by its issuer, so telling the authority's own list from whatever answered the
URL needs the issuer's certificate; and OCSP names a certificate by hashes of its issuer's
name and key, which only the issuer's certificate carries. With none configured, CRLs are
still used and every result says `(signature not verified)` — set `allow-unverified-crl:
false` to have those count as *Unknown* instead.

A certificate that turns out to be revoked is recorded against its entry in the audit trail
and its contacts are told, once — not nightly for as long as it stays revoked. Where to ask
is read out of the certificate when it is parsed and cached with it, so rows cached before
this feature carry no endpoints until a sweep re-reads them.

**A responder for certificates that name none.** The address of an OCSP responder belongs
in the certificate's authority information access extension, and a check has nowhere to go
without one — but an internal CA issuing inside a single network often leaves it out,
because everything that will ever validate the certificate already knows where the
responder is. `default-ocsp-url` is how this application is told:

```yaml
cert-alert:
  revocation:
    default-ocsp-url: http://ocsp.example.gov
```

Only a fallback. A certificate that names its own responder is asked at that one — the
issuer saying where to ask outranks a setting here, and a deployment reading more than one
authority has at most one configured address that is right. A result that did come from the
fallback says `(configured default)` in its detail, so it is never mistaken for the issuer's
own answer. An issuer certificate is still needed either way, for the same reason as above.

**A distribution point for certificates that name none**, on the same terms:

```yaml
cert-alert:
  revocation:
    default-crl-url: http://pki.example.gov/ca.crl
```

Reach for this one first. A CA that leaves out the responder address usually leaves out the
distribution point too, and of the two fallbacks the list is much the cheaper: one download
answering for every certificate the authority issued, against one request per certificate
plus a directory read per entry. It is also the one that cannot be got dangerously wrong — a
list signed by some other authority fails its signature check against the issuer
certificates and counts as *Unknown*.

Where a certificate names neither and neither is configured, the result says so. Where one
*is* configured and the check still could not run — no issuer certificate to name the
certificate by, or the entry could not be read back — the result says which piece was
missing, rather than reporting that the certificate names nowhere to ask. A deployment that
configured a responder is owed the difference.

```
GET  /api/v1/revocation        the counts, and whether OCSP is possible here
POST /api/v1/revocation/check  run it now (admin)
```

**On the admin page**, under *Revocation*, with the counts beside it and the result of the
run beneath. It is the nightly job brought forward rather than a page refresh — for the
morning an authority publishes a revocation and waiting until tomorrow is not the answer.
It reads every authority's list, so on a large directory it is not instant; the button
disables itself while it runs and says how long it took. Reading the counts is open to
anybody signed in, since they are a count of what the cache holds; starting a run is an
administrator's.

### Removing what should not still be published

A revoked certificate that is still in the directory is not untidy, it is a hazard:
anything that reads the directory and does not check revocation will pick it up and use it.
An expired one is merely untidy — it clutters the entry and is counted in every report.
There is an optional job that deletes both.

```yaml
cert-alert:
  ldap:
    certificate-cleanup:
      enabled: true
      cron: "0 0 3 * * SAT"
      expired-after: 90d
      revoked-after: 7d
      max-per-run: 500
```

**Off by default, and more firmly than the prune is.** Everything else here reads the
directory and keeps an index of it. This writes, and what it writes cannot be undone from
here: the value is gone from somebody else's system of record, and the only copy of it was
the one that was deleted. So —

- **Grace periods on both sides.** Ninety days after expiry, because a skewed clock or a
  renewal that ran late must not be enough to have the old certificate deleted out from
  under it; seven days after a revocation is found, which is shorter on purpose.
- **A cap per run.** The first run against a directory nobody has ever cleaned up would
  otherwise rewrite tens of thousands of entries in one go, and a mistake caught after five
  hundred removals is a different kind of morning from one caught after fifty thousand.
  Revoked certificates are taken first, so a run that hits the cap spends it on the ones
  that matter.
- **A preview**, which counts what would go and takes nothing:

```
GET  /api/v1/sync/certificate-cleanup/preview
POST /api/v1/sync/certificate-cleanup          (administrators)
```

- **An audit record per certificate removed**, naming which and why, against the entry it
  was taken from.

The work is one search and one modify per entry: the cache holds a fingerprint rather than
the bytes, so the entry's certificates are read, the values with matching fingerprints are
picked out, and those exact values are deleted in a single modification — never a replace
of the whole attribute, which on a multi-valued one is the difference between removing a
certificate and removing all of them. The directory is written first and the cache second,
so a crash in between leaves a certificate deleted from the directory and still in the
index, which the next sweep corrects. The other order would leave it deleted from the index
and still published.

**The service account needs write on the certificate attribute and nothing else.** Without
it every removal is refused, which is counted and logged rather than retried — the
directory's access control has the last word on this, and should:

```
access to attrs=userCertificate
        by dn.exact="cn=cert-alert,ou=services,dc=example,dc=test" write
        by users read
        by anonymous none
```

### The audit trail

Every entry carries a history: what happened to it, when, and who did it. It shows up in
three places. Expanding a row on either table gives the last ten, newest first — the quick
look, with a link through. The **details page** for that entry carries the whole thing as a
search table of its own: paged, ordered, searchable, and narrowed by kind of event. The
**administration page** carries all of it at once (see below).

| Recorded | When |
|----------|------|
| **Discovered** | the first sweep to see the entry |
| **Certificate published / withdrawn** | the directory started or stopped publishing one |
| **Certificate status changed** | valid → expiring soon → expired, from a sweep or the hourly re-evaluation |
| **Alert sent / failed** | one record per delivery channel, per alert — including the ones that threw |
| **Point of contact added / removed** | somebody edited the managed list |
| **Pruned** | the entry was deleted after going unseen |

What is recorded is **changes**. A sweep that finds a hundred thousand entries exactly as
it left them writes nothing; the sweep itself is already in `sync_run`. An entry seen for
the first time is one record however many certificates it arrived with — those certificates
are what the entry *is*, not something that happened to it, and a record each would bury
every real change under a hundred thousand rows of "we looked".

The actor is whoever was signed in, or the job that did it (`sync`, `changelog`,
`expiry refresh`, `prune`) when nobody was. A sweep somebody triggers from the UI is
recorded as theirs, because it was.

```
audit_event
  subject_type + subject_id   the entry, with no foreign key
  subject_dn + subject_name   copied in, and all that is left after a prune
  actor                       a person, or the job
```

The subject is **not** a foreign key on purpose. An audit record has to outlive what it
describes — the most interesting record of all is the one saying an entry was deleted — and
a foreign key would either take the record with it or refuse the deletion.

Nothing is ever trimmed unless asked: `cert-alert.audit.retention.enabled` is `false`, and
turning it on removes records older than `cert-alert.audit.retention.after` (a year by
default) on a weekly schedule. `cert-alert.audit.enabled: false` stops new records being
written without deleting or hiding what is already there.

### Metrics

`/metrics` reports on the directory as a whole rather than on one entry: how many
certificates are cached and in what state, what is falling due in seven days and thirty and
ninety, what the directory is signing with, and how many people have been told about it.

It also reports on the two things that happen *to* certificates rather than to the clock:

- **Issuance** — how many were issued in the last thirty days, ninety and year, the average
  validity over the last year, and which authorities issued them. A renewal programme that
  has started, or a policy that has gone from three-year certificates to ninety-day ones,
  shows up here before it shows up anywhere else. The average is taken over the last year on
  purpose: a directory holds certificates issued under policies nobody remembers, and
  averaging those in hides what the policy is now.
- **Revocation** — the counts by state, how many were revoked in the last thirty days and
  the last year, the authorities' own reasons, and how stale the oldest answer is. *Not
  checked* is a number of its own, because it is not the same as *not revoked*.

**Where the estate is** comes from the directory's own attributes: people and servers each
broken down by duty organization, duty sub-organization and employee type. Counted over
entries rather than certificates, and the two kinds counted apart — an office with four
hundred people and two servers is a different thing from one with four hundred servers, and
an agency-level count says "Example Agency holds forty thousand certificates", which is true
and of no use to anybody. A value the directory does not publish reads as *not stated*
rather than being left out, because an attribute nobody fills in is itself a finding.

**Issued against expiring** is the chart worth looking at. A bar per month for a year
either side of today: to the left, when certificates were issued; to the right, when they
run out. The bars past the marker are the renewals that have not happened yet, which is the
thing a page like this exists to make visible.

Every number is a grouped count done by the database, including the ones bucketed by month
— reporting on a hundred thousand certificates by reading them into the application and
counting them there would work exactly once, on a small directory.

The chart is inline SVG drawn in the page's own script. Everything this application serves
has to come out of the jar, and two series of twenty-five points do not justify shipping a
charting library to an air-gapped network.

"Channel deliveries" is a different number from "people told": one alert goes to every
configured channel, and a channel that threw is still a delivery that was attempted.

### Projects

The directory knows that a person is in an organization and that a server has a point of
contact. It has no way to say that *these six servers and these four people are one system
that gets renewed together*. That grouping is this application's, and it is what turns
"forty certificates expire this month" into "the payroll migration expires this month".

A project is a name, a description, and two sets. Create one at `/projects`, then add
people and servers to it by name. Both search tables take a **Project** filter, and an
entry's details page lists the projects it is in.

Removing something from a project changes nothing about it: the entry is the directory's,
and the project is only what it is for. Deleting a project deletes the grouping and nothing
else. A prune that removes an entry from the directory takes it out of its projects, which
the database does — a prune is a bulk delete that JPA never sees.

Joining and leaving are recorded against the **entry**, not the project, because the
question is asked while looking at the server: why is this in the payroll project?

### Notifications

An alert is raised once about a certificate. A **notification** is one person's copy of it,
because being told is something that happens to a person, and so is having read it. A
server with four points of contact produces four.

Two paths, deliberately different:

| | When | Where |
|---|---|---|
| **Certificate status** | the moment a sweep or the hourly re-evaluation notices a certificate crossing into a bad state | the page, one per contact |
| **Expiry round-up** | daily, on a cron | the page *and* an email to each contact |

The page is reached through the **bell** in the navigation bar, which carries the unread
count and is the only way in: a tab beside it would be a second control for one destination,
and the bell says something the tab could not.

The alert channels (`cert-alert.alerts.*`) are a different thing again: they tell a fixed
list of operators about every alert as it happens. The round-up writes to the person who
has to renew the certificate.

Recipients are resolved through everything this application knows about who is responsible:
a person's own certificate is theirs, and a server's is its points of contact's — the
directory's `serverPOC` values resolved to people, which now includes group addresses, plus
the contacts added here. An address nobody has claimed is still a recipient by email; it
just has nobody to show a notification to.

Nobody hears the same thing twice inside `cert-alert.notifications.repeat-after` (a week by
default). A nightly sweep and a certificate that stays expiring for a month would otherwise
mean thirty notifications.

**How many days before expiry the round-up writes** is set on the notifications page, not in
the configuration file. It is the number that gets argued about once a deployment is real -
thirty days is too late for a certificate whose renewal needs a change request raised, and a
fortnight of noise for a service that reissues weekly - and the people who know the answer
are rarely the people with access to the YAML. `cert-alert.notifications.digest.window` is
the starting point; the page overrides it, anybody signed in can see what it is set to and
who set it, and only an administrator can change it, because it decides what every point of
contact in the directory hears.

The round-up selects on the expiry date rather than on the cached `EXPIRING_SOON` status,
so setting this beyond `cert-alert.warning-threshold-days` reaches the certificates past that
window rather than quietly reporting the same thirty days. The two are separate on purpose:
the threshold is what the tables and the page alerts call expiring, and this is how far ahead
the email looks.

Email is off until `cert-alert.notifications.email.enabled` is set and `spring.mail.*` is
configured. The notifications are written and shown on the page either way.

The round-up is rendered from Thymeleaf templates in `src/main/resources/templates/email`,
one per kind of thing expiring:

| Template | What it says |
|---|---|
| `expiring-user.html` / `.txt` | the certificates issued to the person reading it, which are theirs to renew |
| `expiring-server.html` / `.txt` | the certificates on servers they are a point of contact for, which somebody else may already be chasing |

Somebody who is both gets one of each rather than a single list that mixes them: the wording,
the subject line and what the first column names all differ. Each message goes out as HTML
with the plain-text alternative beside it, and nothing in either half is fetched when the
message is opened — no stylesheet, no webfont, no tracking pixel — because the network this
runs on may be able to reach nothing at all.

The text templates carry their own template resolver (`EmailTemplateConfig`), because a
resolver carries one template mode; it answers only for `email/*.txt` and leaves every page
to the resolver Spring Boot configures.

#### Until it is renewed, and no longer

A round-up is worth reading only if everything in it still needs doing. A certificate
somebody renewed last week does not, and going on about it for the weeks until the old one
lapses is how a round-up teaches people to ignore it. So an expiring certificate is reported
only while the entry still depends on it, and two questions decide that.

**Has the entry published something in its place?** A renewal is the *same name, for the
same job, issued again* — same identity, same use, new dates, new key. A certificate is
treated as replaced when the entry holds another one with the same subject **and the same
use**, issued later, that has not itself expired. Publish the renewed certificate and the old
one drops off the next round-up, without waiting for it to expire and without anybody marking
anything read.

Both halves of that rule earn their place. **Same subject**, because an entry may publish
certificates for several names, and "the newest one wins" would stop telling somebody about a
credential that is genuinely running out on the strength of an unrelated certificate being
younger. **Same use**, because a person does not hold one certificate: PKI for people issues
a signing certificate and a key encipherment one to the same name in the same breath, and the
second is written to the directory moments after the first. On subject alone the encryption
half reads as a renewal of the signing half — which on a directory where every person holds a
pair means half of everything expiring goes unreported. Renewing one half replaces that half
and leaves the other standing, which is also the case most worth hearing about: a pair
straddling two issuances is the ordinary way to end up half expired.

Of the two ways to be wrong, silence about a certificate nobody has renewed is much the worse
one, so every comparison fails towards saying something.

**Is it what the entry's state is based on?** The same roll-up the tables use: a server that
reads VALID because it publishes one good certificate does not also generate mail about the
one beside it. The page and the message agree, which they would not if the round-up asked a
question of its own.

An entry whose certificates have *all* lapsed is the case that most needs telling, and it
still is: with nothing standing, nothing has been replaced.

#### A dry run

What a round-up would do, asked without doing it. **Dry run** on the notifications page
builds every message and sends none, writes no notifications, and hands back who would hear
from it and what each message says:

```
POST /api/v1/notifications/digest?dryRun=true   (admin)
```

The answer carries the counts and the messages themselves — the address, the subject line
and the rendered text — so one can be read before an estate of a hundred thousand entries is
written to. Up to twenty-five come back; the count says how many there would be in total.

**A rehearsal that builds nothing says why**, on the card and in the answer's `note`. "No
messages" is what comes back from half a dozen different situations — a window nothing falls
inside, a directory that has already renewed everything, points of contact that resolve to
nobody, contacts who publish no address, the per-run cap — and which of them it is decides
whether anybody has anything to do. An empty card that does not say which is a result nobody
can act on.

It runs **whether or not email is switched on**, which is the state it is most wanted in:
"what would this send" is a question asked before `cert-alert.notifications.email.enabled`
is set, not after. The answer says which it was, so `3 email(s) would be sent — once email
is switched on` is never read as three that went.

Everything that can go wrong in the building has already happened by the time a dry run
answers: the templates are rendered, the recipients resolved, the addresses looked up. What
is left untested is the transport.

A whole deployment can be put into it instead, so the scheduled round-up rehearses nightly
and sends nothing:

```yaml
cert-alert:
  notifications:
    email:
      dry-run: true            # CERT_ALERT_EMAIL_DRY_RUN
```

#### Templates of your own

What these messages say is a deployment's own — who signs them, what the internal renewal
process is called, what somebody is meant to do next — and none of that belongs in an image
everybody shares. Point `template-directory` at a directory and the templates in it are used
in place of the packaged ones:

```yaml
cert-alert:
  notifications:
    email:
      template-directory: /etc/cert-alert/templates
```

The layout mirrors the jar, so the files go in an `email` subdirectory:

```
/etc/cert-alert/templates/
└── email/
    ├── expiring-user.html      expiring-user.txt
    └── expiring-server.html    expiring-server.txt
```

**Only what is there is used.** A directory holding one file overrides one template and the
rest still come from the jar, so changing the wording of one message does not mean taking
ownership of all four and keeping them in step with the packaged ones for ever. Start by
copying the one you want to change out of `src/main/resources/templates/email`, since the
model it renders — `digest.recipientName`, `digest.headline`, `digest.entries` and each
entry's fields — is what the templates document.

In a container, mount the directory read-only and name it in the environment:

```yaml
services:
  cert-alert:
    volumes:
      - ./email-templates:/etc/cert-alert/templates:ro
    environment:
      CERT_ALERT_NOTIFICATIONS_EMAIL_TEMPLATE_DIRECTORY: /etc/cert-alert/templates
```

Templates are read once and cached, so an edit to a mounted file takes effect on the next
restart. The setting has to be **absent** to be off rather than empty: an empty value is a
misconfiguration and says so at startup, because the alternative is a resolver silently
pointing at whatever directory the process started in. When a directory is configured, one
line at startup says which:

```
INFO  c.w.c.config.EmailTemplateConfig : Email templates will be read from
      /etc/cert-alert/templates/ before the packaged ones
```

### Editing the directory

The directory is the source of truth and this application is the index over it — with one
exception, made on purpose. An administrator can name **attributes of a server entry that
may be edited from here**, and what somebody types into them is written to the entry with an
LDAP modify. There is no local copy: the value is read from the entry when a page asks for it
and written back when it changes, because an attribute that can be edited in two places is an
attribute that will disagree with itself.

What is defined here is not the value but its shape — which the directory's schema does not
say and the people using it know:

| Kind | Holds | One or several |
|---|---|---|
| Free text | anything typed | either |
| Drop-down | one of the values the definition lists | either |
| Yes or no | `TRUE` on the entry, or the attribute absent | one, always |

A boolean cannot be multi-valued — a second value would have to contradict the first — and
"no" is the attribute's absence, because a directory has no such thing as an attribute that
is present and empty. Clearing any attribute removes it from the entry for the same reason.

```
GET  /api/v1/admin/server-attributes          which attributes are editable   (administrators)
POST /api/v1/admin/server-attributes          make one editable               (administrators)
GET  /api/v1/servers/{id}/attributes          what this entry holds           (anyone signed in)
PUT  /api/v1/servers/{id}/attributes/{defId}  write it to the entry           (administrators)
```

Taking an attribute off the list stops it being offered; the directory goes on holding
whatever it held, because those values were never this application's to remove. Every write
is recorded against the server, so its history says who changed which attribute to what.

A write goes out as a single replace, and the entry is read back afterwards so the cached
row — `ATOStatus` is a column here as well as an attribute there — matches without waiting
for the next sweep. What the directory refuses comes back as it was refused: its schema and
its access control have the last word, and where this application binds anonymously the page
says so rather than pretending.

**The service account needs write access to exactly those attributes and no more.** The
stand-in directory shows the shape of it:

```
access to dn.subtree="ou=servers,dc=example,dc=test"
        attrs=ATOStatus,lifeCycleStatus,icNetworks,description
        by dn.exact="cn=cert-alert,ou=services,dc=example,dc=test" write
        by users read
        by anonymous none
```

#### The administration page

`/admin` is the system audit log: every record, across every entry, in one search table.
Above it are counts — how much trail there is, how much of it is from the last day and the
last week, how many distinct people and jobs appear in it — and the span it covers, which
together say whether anything is running at all.

| Narrowed by | How |
|---|---|
| Kind of event | a select built from the enum, so a new kind appears the day it is added |
| About | people, servers, or both; and a box searching a record's name and DN at once |
| Who did it | part of a name — a person as the directory names them, or a job: `sync`, `changelog`, `expiry refresh`, `prune` |
| When | a date range, either end optional |

An entry's own history stays open to anyone signed in: a person looking at a server can see
what has been done to it, and an audit trail nobody may read holds nobody to account. The
whole trail at once is an administrator's, because read end to end it says who has been
here, what they touched and when. Asking `/api/v1/datatables/audit` without a subject is
that same request, so it checks for itself rather than trusting the page it is usually
reached through.

Records outlive what they describe. A row links to its entry only while there is one to
link to; after a prune the DN it carried is what is left, and the log still says the entry
was pruned.

### Project administrators

A project has members, and some of them **run** it. The role carries exactly two things, and
they are the same thing from two directions:

- the **points of contact** on every server in the project are theirs to manage;
- they are **told when those certificates are expiring** — on the page as a sweep notices,
  and in the daily round-up, alongside the servers' own points of contact.

That is the answer to a directory whose `serverPOC` is stale or names a person who left: the
people who know what a system is for can keep its contacts right, without an administrator in
the loop, and hear about it when they do not.

Only an administrator grants it (`POST /api/v1/projects/{id}/admins/{userId}`), even where
`contact-editors: AUTHENTICATED` lets everybody curate projects — it hands over the contact
lists of every server in the project. Somebody made a project administrator is made a member
too, because there is no running a project from outside it; stepping down leaves them in it,
and leaving it gives up the role. Both are recorded against the person, so their history says
who gave them the project and when.

### The details pages

`/users/{id}` and `/servers/{id}` are a page per entry, reached by clicking its name in
either table. They carry what the expanded row shows and more: every attribute the
directory publishes, each cached certificate in full, and the audit table.

The attributes and certificates are rendered with the page rather than fetched. The search
pages work the other way round — what they show depends on paging and filters the browser
owns — but a details page is about one entry known at request time, so only the two things
that change while it is open are fetched: the points of contact, which are editable, and
the audit table, which pages and searches.

A server's page carries the same contacts editor as its row on the search table. A person's
page also lists **what a `serverPOC` could name them by** — every address and every form of
their name — which is the join key, and links through to the servers they are the contact
for.

### Other endpoints

| Method | Path                                | Purpose                                  |
|--------|-------------------------------------|------------------------------------------|
| `POST` | `/api/v1/sync`                      | Run both sweeps now                      |
| `POST` | `/api/v1/sync/users`                | Scrape IC Persons now                    |
| `POST` | `/api/v1/sync/servers`              | Scrape IC Non-Person Entities now        |
| `POST` | `/api/v1/sync/refresh`              | Re-evaluate cached expiry                |
| `POST` | `/api/v1/sync/prune`                | Remove entries unseen past the window     |
| `POST` | `/api/v1/sync/certificate-cleanup`  | Delete finished certificates from the directory |
| `GET`  | `/api/v1/sync/certificate-cleanup/preview` | What that would delete            |
| `GET`  | `/api/v1/sync/prune/preview`        | How many the next prune would remove      |
| `GET`  | `/api/v1/sync/runs`                 | The run log, newest first                |
| `GET`  | `/api/v1/stats/users`               | Certificate roll-up across IC Persons    |
| `GET`  | `/api/v1/stats/servers`             | Certificate roll-up across servers       |
| `GET`  | `/api/v1/users/{id}/certificates`   | Cached certificate detail for a person   |
| `GET`  | `/api/v1/servers/{id}/certificates` | Cached certificate detail for a server   |
| `POST` | `/api/v1/servers/{id}/probe?port=`  | Ask the endpoint what it is serving       |
| `GET`  | `/api/v1/revocation`                | Revocation counts, and whether OCSP works |
| `POST` | `/api/v1/revocation/check`          | Run the revocation check now              |
| `GET`  | `/api/v1/servers/{id}/contacts`     | Both lists of points of contact          |
| `POST` | `/api/v1/servers/{id}/contacts`     | Add one: `{"userId":…}` or `{"email":…}` |
| `DELETE` | `/api/v1/servers/{id}/contacts/{contactId}` | Remove one                   |
| `GET`  | `/api/v1/users/search?q=`           | People matching, for the contact picker  |
| `GET`  | `/api/v1/metrics`                   | Everything the metrics page shows        |
| `GET`  | `/api/v1/projects`                  | Every project, with its two counts       |
| `POST` | `/api/v1/projects`                  | Create one                               |
| `PUT`  | `/api/v1/projects/{id}`             | Rename it                                |
| `DELETE` | `/api/v1/projects/{id}`           | Delete the grouping, not what is grouped |
| `POST`/`DELETE` | `/api/v1/projects/{id}/users/{userId}` | Add or remove a person        |
| `POST`/`DELETE` | `/api/v1/projects/{id}/servers/{serverId}` | Add or remove a server    |
| `GET`  | `/api/v1/servers/search?q=`         | Servers matching, for the project picker |
| `GET`  | `/api/v1/notifications`             | Your notifications, `?page=&size=`       |
| `GET`  | `/api/v1/notifications/unread-count` | What the bell counts                    |
| `POST` | `/api/v1/notifications/{id}/read`   | Mark one read                            |
| `POST` | `/api/v1/notifications/read-all`    | Mark them all read                       |
| `POST` | `/api/v1/notifications/digest`      | Run the expiry round-up now, `?dryRun=true` to rehearse it |
| `GET`  | `/api/v1/notifications/settings`    | How far ahead the round-up looks         |
| `PUT`  | `/api/v1/notifications/settings`    | Set it, `{"leadDays": 45}`               |
| `GET`  | `/api/v1/users/{id}/addresses`      | Both lists of addresses                  |
| `POST` | `/api/v1/users/{id}/addresses`      | Add one: `{"address":…,"kind":"GROUP"}`  |
| `DELETE` | `/api/v1/users/{id}/addresses/{aliasId}` | Remove one                       |
| `GET`  | `/api/v1/users/{id}/audit`          | A person's history, `?page=&size=`       |
| `GET`  | `/api/v1/servers/{id}/audit`        | A server's history, `?page=&size=`       |
| `POST` | `/api/v1/datatables/audit`          | One entry's history as a search table, `?subjectType=&subjectId=&action=` |

Errors come back as RFC 7807 problem details. Actuator is at `/actuator`
(`health`, `info`, `metrics`, `loggers`); the LDAP health indicator reports the
directory connection.

### When something goes wrong

`GlobalExceptionHandler` is the one place an exception that escapes a controller
becomes an answer, and it renders that answer in whichever of two forms the caller
can use. The exception sets the status and the wording; the address decides the
form. Anything under `/api` or `/actuator` is problem detail whatever the request
said it accepts, because a browser pointed at an endpoint is still an endpoint.
Everywhere else is a page, unless the caller named a machine format and not HTML.

Pages come from two templates. `not-found.html` answers for an entry that is not
there, which has something particular to say - the directory may have stopped
publishing it. `error.html` answers for everything else, choosing its wording from
the status rather than printing the exception, since `server.error.include-message`
is `never` and a stack trace tells an attacker more than it tells the reader.

`error.html` is also what Spring Boot's own error dispatch renders, so a denial
from the security filter chain - which reaches no exception handler at all - comes
out as the same page rather than the Whitelabel one.

## The scheduled jobs

Four on the directory, one that asks the issuing authorities, and one that trims the audit
trail. All are evaluated in UTC; the first four are configured under `cert-alert.ldap`,
revocation under `cert-alert.revocation` and the trim under `cert-alert.audit`.

| Job         | Default cron      | What it does                                             |
|-------------|-------------------|----------------------------------------------------------|
| **users**   | `0 0 2 * * *`     | Scrapes IC Persons                                        |
| **servers** | `0 0 4 * * *`     | Scrapes IC Non-Person Entities, staggered from the above  |
| **refresh** | `0 15 * * * *`    | Re-evaluates cached expiry; reads no LDAP                 |
| **revocation** | `0 30 5 * * *` | Asks the authorities what they have revoked; reads no LDAP |
| **certificate cleanup** | `0 0 3 * * SAT` | Deletes finished certificates from the directory; off unless turned on |
| **prune**   | `0 0 6 * * SUN`   | Removes entries the directory has stopped publishing      |
| **audit retention** | `0 30 3 * * SUN` | Trims the audit trail; off unless turned on        |
| **expiry round-up** | `0 0 7 * * *` | Tells each point of contact what of theirs is expiring |

Alongside them, the [changelog connector](#following-the-changelog) follows the directory
continuously, so the sweeps are a backstop rather than the only way a change arrives.

**Why refresh is its own job.** A certificate moves from valid to expiring to expired purely
because time passes — nothing in the directory changes. Without it, a status would only be
corrected when its entry happened to be re-scraped, so a nightly sweep would mean expiry
alerts up to a day late. It walks only certificates that could plausibly have moved (not
already expired, notAfter inside the warning window), which is a small indexed slice.

**Why revocation is its own job.** It is the only work here that reads neither the directory
nor the clock: it asks the certificates' own authorities, which is a different system, on a
different network path, with its own failure modes. It runs after both sweeps so it is
asking about a current cache. See [Revocation](#revocation).

**Why the certificate cleanup is off by default.** It is the only job here that writes to
the directory, and deletions there cannot be undone from this side. See
[Removing what should not still be published](#removing-what-should-not-still-be-published).

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

### The first start imports the directory

Following a changelog keeps a cache current; it never populates one. With `start-from:
LATEST` the connector takes up position at the directory's newest change and applies what
happens after it, so switched on against an empty cache and left alone it would leave that
cache empty until the nightly sweep. So the first poll with no stored position reads the
whole tree first, through the same sweep the schedule runs — recorded in `sync_run` and
visible at `GET /api/v1/sync/runs` like any other. Enabling the connector is enough on its
own.

It runs **once**, when `changelog_cursor` holds no row — not on every restart, which resumes
from the stored position as before. The changelog bounds are read before the import and the
cursor written after it, so a change made while the tree is being read is applied again
rather than lost, and an import that fails leaves no position behind for the connector to
follow on from. Set `full-sync-on-first-run: false` where the baseline is established some
other way and a full read at startup is not wanted.

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
is removed rather than left behind as a stale row.

Whether a changed DN is a person, a server or neither takes two tests, not one. The
sweep's **search filter** says what shape the entry has to be, and its **search base** says
where it has to live; the connector applies both, so what it caches is exactly what a
sweep would have collected.

The base matters because a changelog is not scoped to what this application reads. A
directory records whatever it was configured to record, usually more of the tree than
either sweep covers, and `targetDN` is an absolute name the connector re-reads over a
base-less connection. On the filter alone, a person under `ou=contractors` — or under a
different suffix entirely — would be cached from a change although no sweep would ever
see it, leaving rows the sweeps can neither refresh nor account for. Such an entry is now
ignored, and one already cached from before is dropped the next time the changelog names
it, so a cache that collected them settles itself.

The base each type is held to is its own `search-base` resolved under `spring.ldap.base` —
`ou=people,dc=example,dc=gov` for the defaults. Leave both empty and there is no
constraint, which is correct: a sweep with no base reads the whole tree, so the connector
does too. Names are compared as distinguished names rather than as text, so case and
spacing around the commas do not matter.

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

**Where it has been run.** Against 389 Directory Server's retro changelog, which is what
the compose stand-in publishes: switch the connector on and an `ldapmodify` against the
directory shows up in the cache within a poll, with no sweep in between. Add, modify,
rename and delete all arrive; a write to something neither sweep would collect is counted
and stepped over. The tests drive the same paths against the in-memory server's own
changelog. The `dev` profile's embedded directory publishes none, so the connector stays
off there.

**Caveats.** It runs in every instance that starts it; applying is idempotent so a second
one is harmless rather than wrong, but it is wasted work — set `auto-start: false` on all
but one, or add leader election.

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
| `banner.enabled` | `false` | Fixed classification bars top and bottom; nothing shows until `banner.text` is set |
| `banner.text` / `.text-color` / `.background` / `.height` | — / `#ffffff` / `#1d273b` / `1.5rem` | What they say and how they look. A colour or length that is not one falls back to the default |

Scraping, under `cert-alert.ldap`:

| Property             | Default | Purpose                                        |
|----------------------|---------|------------------------------------------------|
| `page-size`          | `500`   | Entries per LDAP page                           |
| `paged`              | `true`  | Turn off only for servers without the control   |
| `batch-size`         | `200`   | Entries written per transaction                 |
| `count-limit`        | `0`     | Client-side cap on entries; 0 means none        |
| `search-timeout`     | `10m`   | Per-search time limit                           |
| `binary-certificate-option` | `true` | Ask for `userCertificate;binary`. Off for a directory with no such attribute to return |
| `server.require-email-poc` | `true` | A `serverPOC` value must be an email address; off for a directory holding names. Comma-separated values are split either way |
| `sync.enabled`       | `true`  | Set false to drive every job through the API    |
| `sync.users-cron`    | `0 0 2 * * *`  | IC Person sweep                          |
| `sync.servers-cron`  | `0 0 4 * * *`  | IC Non-Person Entity sweep               |
| `sync.refresh-cron`  | `0 15 * * * *` | Expiry re-evaluation                     |
| `certificate-cleanup.enabled` | `false` | Whether finished certificates are deleted from the directory |
| `certificate-cleanup.cron` | `0 0 3 * * SAT` | When that runs                       |
| `certificate-cleanup.remove-expired` / `.expired-after` | `true` / `90d` | Delete certificates finished with for this long |
| `certificate-cleanup.remove-revoked` / `.revoked-after` | `true` / `7d` | Delete revoked ones this long after finding out |
| `certificate-cleanup.max-per-run` | `500` | Most removals in one run                  |
| `prune.enabled`      | `false` | Whether stale entries are deleted at all        |
| `prune.cron`         | `0 0 6 * * SUN` | Prune schedule                          |
| `prune.after`        | `30d`   | How long an entry may go unseen before deletion |
| `changelog.enabled`  | `false` | Follow the directory's changelog continuously   |
| `changelog.auto-start` | `true` | Whether the loop starts with the application   |
| `changelog.base-dn`  | `cn=changelog` | The changelog suffix                     |
| `changelog.poll-interval` | `10s` | Wait after a poll that found nothing      |
| `changelog.batch-size` | `500` | Most changes read in one poll                  |
| `changelog.start-from` | `LATEST` | `LATEST` or `BEGINNING`, with no stored position |
| `changelog.full-sync-on-first-run` | `true` | Import the directory on the first start, before following |
| `changelog.full-sync-on-gap` | `true` | Sweep when the changelog has been trimmed past the cursor |
| `user.*`, `server.*` | FSD names | Search base, filter, and attribute names      |

What counts as one issuance, under `cert-alert.credentials`:

| Property      | Default | Purpose                                                     |
|---------------|---------|-------------------------------------------------------------|
| `pair-window` | `7d`    | How far apart a person's signing and encryption certificates may be issued and still be one renewal |

Revocation, under `cert-alert.revocation`:

| Property               | Default             | Purpose                                    |
|------------------------|---------------------|--------------------------------------------|
| `enabled`              | `true`              | Whether revocation is checked at all        |
| `cron`                 | `0 30 5 * * *`      | When the scheduled check runs               |
| `issuer-directory`     | unset               | CA certificates, for verifying CRLs and for OCSP |
| `default-ocsp-url`     | unset               | Responder to ask about a certificate that names none; the certificate's own always wins |
| `allow-unverified-crl` | `true`              | Believe a CRL whose signature could not be checked |
| `crl-cache-ttl`        | `6h`                | Ceiling on how long a fetched list is reused |
| `max-crl-bytes`        | `16777216`          | A larger list is refused rather than read    |
| `connect-timeout`      | `10s`               | Reaching a distribution point                |
| `read-timeout`         | `30s`               | Downloading from one                         |

The endpoint probe, under `cert-alert.probe`:

| Property          | Default | Purpose                                              |
|-------------------|---------|------------------------------------------------------|
| `enabled`         | `true`  | Off removes the card and refuses the endpoint         |
| `default-port`    | `443`   | Offered when the entry's URL names no port            |
| `connect-timeout` | `5s`    | How long to wait for the connection                   |
| `read-timeout`    | `5s`    | How long to wait for the handshake                    |

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
docker-compose.yml       the application, a PostgreSQL and a stand-in directory
docker/389ds/            389 Directory Server carrying the IC FSD schema and a changelog
scripts/                 the dummy directory generator
src/main/java/com/winllc/certalert/
├── alert/       notifier SPI, dispatcher, log and email channels
├── config/      expiry thresholds, clock, DataTables repository factory
├── domain/      DirectoryUser, DirectoryServer, CachedCertificate, ServerContact, AuditEvent
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
│                          two search pages, two details pages, login
└── dev-directory.ldif     sample directory for the dev profile
```

## Next steps

- Group-derived roles, for directories that express administrators as a group rather than
  a list of people
- Distributed locking (ShedLock or similar) if more than one instance will run the jobs or
  the changelog connector; the overlap guard is per-process
- OCSP on the scheduled job as well as on a single check, once there is a deployment whose
  responder would rather be asked a hundred thousand times than publish a list
- Certificate chain validation, not just the leaf
- More alert channels (Slack, PagerDuty, webhooks)

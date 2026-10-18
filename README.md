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
| Demo        | UnboundID in-memory directory, generated and served in-process |

## Licensing

cert-alert is proprietary software of ENS Solutions ([`LICENSE`](LICENSE), SPDX
`LicenseRef-ENS-Commercial`, as the image's and the chart's license labels say), used under its
end user license agreement ([`EULA`](EULA) - a placeholder until ENS Solutions puts its legal
text there, to be replaced before a release): its source being visible does not license it. It needs an **ENS license** to run. Licenses are issued by the ENS licensing portal
(an evaluation, or the license issued against an order) in the portal's `ens-license/1`
format, for the product id **`certalert`**, and are checked offline: the application
verifies the Ed25519 signature against the ENS public key built into it
(`license/EnsKeys.java`; not configurable), so no network is involved. The verifier is the
portal's zero-dependency library, copied unchanged into `com.enssolutions.license`.

### What the license does

| State (`/api/v1/license`) | Mode | What works |
|---|---|---|
| `VALID`, `GRACE` (expired, within its grace days) | full | Everything the license's features include. In grace, administrators see a renewal reminder (also in the last 30 days) |
| `EXPIRED` (past grace), `RELEASE_NOT_COVERED` (perpetual, this build is newer than its maintenance) | read-only | Everything readable — pages, tables, exports. Nothing that changes data (403 `license_read_only`); directory sweeps, scheduled jobs, the changelog connector and outgoing email pause |
| `MISSING`, `INVALID`, `WRONG_PRODUCT`, `WRONG_INSTALLATION`, `SUPERSEDED`, `NOT_YET_VALID` | unlicensed | Only signing in and out, static assets, health, the error page and the license screen. Administrators are sent to the license screen, anyone else to a page saying to ask one; the API answers 403 `license_required` |

An evaluation license puts an **Evaluation** strip on every page. Refusals are problem
detail like every other error here, with `error` (`license_required`, `license_read_only`
or `feature_not_licensed`), the license `state` and its `message`:

```json
{"title":"License required","status":403,"error":"license_required","state":"MISSING",
 "message":"No license is installed. Upload one on the license screen.", ...}
```

### Features

Every feature in the portal's catalog is gated, including the ones every license carries
today, so moving one between tiers needs only a change on the portal. Endpoints carry
`@RequiresFeature` (403 `feature_not_licensed` with the `feature` id when it is not
included); jobs, menus, cards and buttons ask `Licensing` and are hidden when it is not.

| Feature id | What it gates |
|---|---|
| `directory-sync` | The scheduled user and server sweeps and the expiry refresh; `POST /api/v1/sync`, `/sync/users`, `/sync/servers`, `/sync/refresh`; the Sync directory button. Every license has it |
| `changelog-sync` | `/api/v1/changelog/**` and the changelog connector's polling; the header's changelog badge |
| `notifications` | Email: the expiry digest job and `POST /api/v1/notifications/digest`, the email alert channel, digest email. In-app notifications stay available |
| `metrics` | `/metrics`, `/api/v1/metrics`, the Metrics menu item |
| `projects` | `/projects/**`, `/api/v1/projects/**`, the Projects menu item, project filters and cards |
| `revocation-checks` | `/api/v1/revocation/**`, the revocation job, the administration page's revocation card |
| `endpoint-checks` | `POST /api/v1/servers/{id}/probe` and the probe card on a server's page |
| `risky-names` | Risk analysis: the risky-name badges, filters and metrics (flags are still computed during a sweep, so they appear at once when it is licensed) |
| `directory-cleanup` | `/api/v1/sync/prune/**`, `/api/v1/sync/certificate-cleanup/**` and their scheduled jobs |
| `classification-banners` | The configured classification banner (`cert-alert.banner`): not drawn without it |
| `multi-directory` (Enterprise) | Not yet implemented; listed on the license screen only |

In read-only mode what a listed feature has gathered stays readable (its pages and `GET`
endpoints), but nothing runs.

**Size.** A license is sized in **directory users** — the people in the cache. The license
screen shows the count against it. Up to 10% over, sweeps carry on with a warning; beyond
that a sweep (or the changelog) stops adding *new* people until the license is updated. Nobody
already cached is ever removed, and servers are not counted.

### The license screen

**Administration → License** (`/admin/license`, administrators only, reachable whatever
the license says) shows the state and its message, the licensee, edition, dates, grace and
maintenance, every catalog feature ticked or not, the size in use, and **this
installation's id** (e.g. `CA-7F3K-29QD`, with a copy button): enter it on the portal to
bind a license to this installation. Upload a `.lic` file, or paste one, to install it; it
takes effect at once. A file for another product, bound to another installation, or an
older revision of a license already seen here is refused with the reason, and the
installed one stays. `GET /api/v1/license` returns the same as JSON to anyone signed in —
the installation id and the file's path to administrators only.

### Where it is kept

One persistent, **writable** directory holds the license, the installation id and the
highest revision seen of each license (so an older file cannot be put back):

| Environment | Default outside a container | In the image |
|---|---|---|
| `ENS_LICENSE_FILE` | `/etc/ens/certalert.lic` | `/var/lib/ens/certalert/license.lic` |
| `ENS_LICENSE_STATE_DIR` | `/var/lib/ens/certalert` | `/var/lib/ens/certalert` |

The image declares `VOLUME /var/lib/ens`; mount a named volume or persistent volume claim
there so the license and the installation id survive upgrades (lose it, and the
installation gets a new id, so bound licenses must be re-bound). The release package keeps
it in the `ens-license` volume; the Helm chart keeps it in a PVC mounted at `/var/lib/ens`
(`persistence.license`). Running from
Gradle or a jar, point both variables somewhere writable, e.g.
`ENS_LICENSE_STATE_DIR=$HOME/.ens/certalert ENS_LICENSE_FILE=$HOME/.ens/certalert/license.lic`.

```bash
docker run -d -p 8080:8080 -v ens-license:/var/lib/ens/certalert cert-alert:latest
# then upload the license on /admin/license, or copy it in:
docker cp license.lic <container>:/var/lib/ens/certalert/license.lic
```

The release bundle's `install.sh` copies `license/license.lic` into the `ens-license`
volume (see `deploy/release/README.md`), and the Helm chart's init container copies
`license.file` or `license.existingSecret` into its PVC - both only when no license is
installed there yet, so a newer one uploaded on the license screen is never replaced.
A license is never built into the image: `.dockerignore` and `.gitignore` exclude `*.lic`.

**Perpetual licenses** cover releases built up to their maintenance date. The release date
is this build's time, from Spring Boot's build-info (`springBoot { buildInfo() }`).

**Tests** trust the portal's published TEST key instead (`support/TestLicenseConfiguration`
replaces the `LicenseManager` for the `test` profile, with a full license installed), and
`support/TestLicenses` signs test licenses with it. Nothing signed with it is accepted by a
build anybody runs.

## Install

There are two supported ways to install cert-alert. Both run the published, tested image
(`ghcr.io/winllc/cert-alert:<version>`) against PostgreSQL, your LDAP directory and, if you
want email, your SMTP server. The install guide covers both in full:
<https://winllc.github.io/ens-apps-documentation/cert-alert/install/>

- **The release package** - `cert-alert-<version>.tar.gz`, signed and checksummed (see
  [Releases](#releases)). Docker Compose with the images inside, so it installs on a host with
  no internet access: unpack, `./install.sh`, fill in `.env`, `./install.sh` again. Upgrades
  with `./upgrade.sh --from <previous release directory>`. Its `README.md` (from
  [`deploy/release/`](deploy/release/README.md)) has the details.
- **The Helm chart** - [`deploy/helm/cert-alert`](deploy/helm/cert-alert/README.md), published
  with every release to `oci://ghcr.io/winllc/charts/cert-alert` and included in the release
  download as `cert-alert-<version>.tgz`:

  ```bash
  helm install cert-alert oci://ghcr.io/winllc/charts/cert-alert --version <version> \
    --set ldap.url=ldaps://directory.example.gov:636 --set ldap.base=dc=example,dc=gov \
    --set database.host=postgres.example.gov -f my-secrets.yaml
  ```

  One replica, a PVC for the license and installation id, an optional evaluation-only
  PostgreSQL, Ingress or OpenShift Route, and TLS passthrough for X.509 sign-in. Every
  setting in the release package's `env.template` has a value in the chart.

Either way, an administrator then installs the ENS license on the license screen
(`/admin/license`), or hands it to the installer (`license/license.lic` for the package,
`--set-file license.file=...` for the chart), and presses **Sync directory**.

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
`CERT_ALERT_LDAP_BINARY_CERTIFICATE_OPTION=false` in the release package's `.env`, or
`ldap.binaryCertificateOption: false` in the Helm chart's values.

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

## Development

**For contributors, not an installation method.** Everything below - the `dev` profile,
Gradle builds, `docker build` and the compose stack in the source tree - is for working on
cert-alert. To install it, use the release package or the Helm chart ([Install](#install)).

### The `dev` profile

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

The `dev` profile runs against an **embedded sample directory** (four people, four
servers, a mix of valid, expiring and expired certificates) so the UI works with no LDAP
server to hand. Open <http://localhost:8080/users> and sign in as `alice` / `password` —
the sample gives everyone that password, and alice is the configured administrator. Like
any installation it needs a license (an evaluation from the ENS portal): you are sent to
the license screen to upload it, and need `ENS_LICENSE_STATE_DIR` and `ENS_LICENSE_FILE`
pointed somewhere writable (see [Licensing](#where-it-is-kept)). Then press **Sync
directory**.

The sample's expiry dates are baked in and will age; regenerate it by re-enabling and
running `DevLdifGenerator`. Neither the in-memory directory nor its fixture reaches the
built jar.

```bash
./gradlew test    # run the test suite
./gradlew build   # compile, test, and produce the executable jar
java -jar build/libs/cert-alert-0.0.1-SNAPSHOT.jar
```

### As a container

Every push builds the image and, once it has been started and answered its health probe,
publishes it to the GitHub Container Registry:

```bash
docker pull ghcr.io/winllc/cert-alert:latest          # the default branch
docker pull ghcr.io/winllc/cert-alert:sha-1a2b3c4     # one commit, forever
```

Four kinds of tag. `sha-<short>` on every push, which is the one to test a commit with — it
never moves.
The branch name, which does. `latest`, only from the default branch. And on a `v*` tag, the
version and its major.minor. Nothing is published from a pull request.

What is published is the image that was tested, not a rebuild of it: the one that answered
the probe and passed the vulnerability scan is the one that goes to the registry.

**Every image is scanned** with Trivy (`--severity HIGH,CRITICAL --ignore-unfixed`), on every
push and pull request, and a finding fails the build. Accepted risks are listed in
`.trivyignore`, each with its reason and a date to look at it again. CodeQL analyses the Java
and the page scripts on every push and weekly. Dependabot proposes updates weekly for the
Gradle dependencies, the base images and the workflow actions — all of which are pinned, the
images by digest and the actions by commit.

Or build it yourself:

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
for, which inside an accredited network is worth more than a smaller image. Both are
pinned by digest on the `FROM` lines (the tag is kept to say which release), so a build is
reproducible and patched bases arrive as Dependabot pull requests; moving to UBI 10 is an
edit of those lines.

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
  -v ens-license:/var/lib/ens/certalert \
  cert-alert:latest
```

Or with a database and a directory alongside it:

```bash
docker compose up --build
```

That brings up three services — PostgreSQL, a stand-in 389 Directory Server, and the
application —
and waits for the first two to be healthy before starting the third.

**Nothing is published to the host.** Everything in the file has a default password and the
directory serves plaintext LDAP, so binding any of it to an interface is opt-in rather than
what happens by default. The services still reach each other over the compose network, and
`docker compose exec` reaches them from a shell.

To reach the UI from a browser, add the ports overlay:

```bash
docker compose -f docker-compose.yml -f docker-compose.ports.yml up --build
```

Then sign in at <http://localhost:8181> as `alice` / `password`. The first time, you are
sent to the license screen: upload an evaluation license from the ENS licensing portal (or
`docker compose cp license/license.lic cert-alert:/var/lib/ens/certalert/license.lic`). It
is kept in the `ens-license` volume. Then press **Sync directory**. `CERT_ALERT_PORT` and `CERT_ALERT_LDAP_PORT` move the host side —
`CERT_ALERT_PORT=9090 docker compose -f docker-compose.yml -f docker-compose.ports.yml up`.
Only the host side moves: the application still listens on 8080 inside the container, which
is what the image's `EXPOSE`, its `HEALTHCHECK` and every probe path assume.

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
looks like one: plaintext LDAP, and a default password on everything — which is why it is
not published to the host unless you ask for it.

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

Or mount an LDIF of your own at `/bootstrap` and it loads that instead. `ldapsearch` is in
the image, so it works against the directory without publishing anything:

```bash
docker compose exec directory ldapsearch -x -H ldap://localhost:3389 \
  -D 'cn=cert-alert,ou=services,dc=example,dc=test' -w cert-alert \
  -b 'dc=example,dc=test' '(objectClass=icOrgServer)' cn serverPOC
```

With the ports overlay up you can run the same query from the host against
`ldap://localhost:1389` instead.

One thing it cannot demonstrate: it serves plain LDAP, so X.509 sign-in still needs
keystores of your own.

#### Generating the data

`scripts/generate-directory-data.sh` writes the LDIF, and is what the image runs on first
boot. It needs bash and openssl and nothing else, so it is equally useful for filling a
directory of your own:

```bash
./scripts/generate-directory-data.sh --users 500 --servers 200 > directory.ldif
# Into the stand-in, which publishes no port: copy it in, then add it from inside.
docker compose cp directory.ldif directory:/tmp/directory.ldif
# -c because the LDIF carries ou=people and ou=servers, which a directory may already have
docker compose exec directory ldapadd -c -x -H ldap://localhost:3389 \
  -D 'cn=Directory Manager' -w directory-manager -f /tmp/directory.ldif
```

Against a directory of your own, that is an ordinary `ldapadd -H ldap://your-host` with no
container involved.

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

- It runs as **uid 185**, not root — the base image's own user. Nothing it does needs privilege.
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
- The license lives in `/var/lib/ens/certalert` (see [Licensing](#where-it-is-kept)), a
  directory owned by uid 185 and group 0 with the group able to write, as OpenShift's
  arbitrary uid needs; mount a persistent volume there. `.dockerignore` excludes `*.lic` so
  one cannot be baked in.
- The image can serve a directory of its own, because the LDAP SDK ships in the jar for
  [demo mode](#demo-mode) — a complete instance in one container, with nothing to point it
  at:

  ```bash
  docker run --rm -p 8080:8080 -v cert-alert-demo-license:/var/lib/ens/certalert \
    cert-alert:latest --spring.profiles.active=demo
  ```

  That is for showing the application, never for running it: it publishes working
  credentials on its own sign-in page. It needs a license too — see
  [Demo mode](#demo-mode).

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

## FIPS 140-3

CertAlert implements no cryptography of its own. Every hash, signature check, key exchange
and cipher - TLS to the browser (1.2 and 1.3 only), the directory, the mail server,
PostgreSQL, Splunk and syslog; the SHA-256 fingerprints and the audit chain; CRL and OCSP
verification; the license signature - goes through the Java platform's security providers
(JCA/JSSE). FIPS 140-3 therefore comes from the platform: run the image, which is built on
Red Hat's UBI 9 OpenJDK, on a RHEL host or OpenShift cluster booted in FIPS mode, where Red
Hat's OpenJDK switches itself to the validated cryptographic modules (the `fips` security
profile; see Red Hat's *Configuring OpenJDK on RHEL with FIPS*). The application has no
setting of its own for that, and claims no validation of its own: the validated module is the
platform's.

**The license check is not yet supported in FIPS mode.** An ENS license is signed with
Ed25519, which CertAlert verifies through the platform's providers like everything else. It
has not been tested whether a host in FIPS mode offers Ed25519 to it; where it does not,
CertAlert can refuse a valid license (as one whose signature could not be checked) or fail
to start. Version 1.1.0 of the ENS license library adds an ECDSA P-256 co-signature so that a
license can be checked with the FIPS-mode providers; CertAlert does not use that version yet.
Until a release says otherwise here, do not rely on CertAlert accepting its license on a host
in FIPS mode.

Not appropriate on a FIPS system, and off by default:

- `ENS_AUDIT_SPLUNK_INSECURE=true` - skips TLS verification to Splunk (a warning is logged).
- `cert-alert.revocation.allow-unverified-crl` (default `true`) - believes a CRL whose
  signature could not be checked; set `false` and configure `issuer-directory`.
- Plain `ldap://`, plain syslog (`udp`/`tcp`), SMTP without `STARTTLS`, and a PostgreSQL
  URL without `sslmode=verify-full`.
- `ENS_X509_REVOCATION_FAIL=open`, and the soft-fail default it stands for when
  `ENS_X509_REVOCATION` is unset; set `ENS_X509_REVOCATION_FAIL=closed`. And
  `ENS_X509_REVOCATION=off`.
- `ENS_X509_SOURCE=header`, or a Helm install whose ingress or edge/reencrypt Route
  terminates TLS: the browser's TLS then ends at the proxy, so it is the proxy's
  cryptographic module, not this platform's, that has to be validated.

Notes: the server-endpoint probe deliberately trusts every chain, because it reads and reports
certificates rather than relying on them. OCSP requests name the certificate by SHA-1 hashes,
as RFC 6960 has them - a non-signature use of SHA-1 that FIPS 140-3 permits. Key and trust
stores have to be in a format the FIPS-mode providers can open (in RHEL's FIPS mode, PKCS#12
created with approved algorithms, or the NSS database).

### FIPS test results for the license library

*Placeholder.* The results of running the ENS license library's tests under the FIPS-mode
providers go here once CertAlert has moved to version 1.1.0 of the library. Until then, see
above: the license check is not supported in FIPS mode.

## Metrics, health and logs

The ENS platform baseline:

- **Prometheus** at `/actuator/prometheus`, **only on the management port**: set
  `MANAGEMENT_SERVER_PORT` (e.g. `9090`, Spring Boot's own setting) and the actuator moves to
  that port, where health and Prometheus are open without signing in — so never publish it
  beyond your monitoring network. Unset (the default), Prometheus is not served at all, and the
  application's port keeps the actuator exactly as before (`health` and `info` open, `metrics`
  and `loggers` for administrators). `/livez` and `/readyz` stay on the application's port
  either way. The Helm chart sets the port (`metrics.enabled`, default on, port `9090`) and
  points its probes at it.
- **Health**: `/actuator/health/liveness` is the process alone; `/actuator/health/readiness`
  is the database and the audit log being writable (`auditLog`: the `ENS_AUDIT_FILE` output,
  when set). The directory is deliberately not in readiness — everything is served from the
  cache, and certificates sign in from it — add `ldap` with
  `MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE=readinessState,db,auditLog,ldap` to make it
  one. Details are shown only to administrators.
- **Business metrics** (Prometheus names):

| Metric | Type | |
|---|---|---|
| `ens_license_mode{mode}` | gauge | 1 for the current mode: `FULL`, `READ_ONLY`, `UNLICENSED` |
| `ens_license_days_remaining` | gauge | Days until the license expires (or its grace ends); NaN for a perpetual one |
| `ens_audit_events_total{category,outcome}` | counter | Events written to the audit trail |
| `ens_audit_forward_failures_total{output}` | counter | Failed attempts to forward them (`splunk`, `syslog`) |
| `ens_auth_attempts_total{method,outcome}` | counter | Sign-ins and refusals: `x509` (once per session) or `ldap`, `success` or `failure` |
| `ens_certalert_certificates{status}` | gauge | Cached certificates by state: `none`, `valid`, `expiring_soon`, `expired` |
| `ens_certalert_job_last_success_seconds{job}` | gauge | When each job last finished without failing (Unix time), since startup |
| `ens_certalert_digest_truncated_total` | counter | Round-ups that stopped at `cert-alert.notifications.digest.scan-limit` (either read) |
| `ens_certalert_revocation_unknown` | gauge | Cached certificates whose authority gave no revocation answer |
| `ens_certalert_mail_outbox{state}` | gauge | Email `pending` in the outbox, and `failed` (given up on) |

  The counts are read from the database at most once a minute.
- **Logs**: `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` (Spring Boot's own setting) writes the
  application log as one ECS JSON object per line. The Helm chart does so by default
  (`logFormat: json`). The audit trail's stdout lines are raw `ens-audit/1` JSON either way.
- **Alerts**: the Helm chart's `metrics.prometheusRule.enabled` (off by default) renders a
  Prometheus Operator PrometheusRule from these metrics: a job that has not succeeded for too
  long, a round-up cut short at its scan limit, certificates with no revocation answer, and
  certificates expiring. See the chart's README, "Metrics and logs".

## Security headers, cookies and TLS

The ENS platform baseline, the same in every ENS product:

- **Content-Security-Policy** on every response: `default-src 'self'; script-src 'self';
  style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:;
  connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self';
  object-src 'none'`. Scripts come from this application only: no page has an inline script
  or an event-handler attribute, and a test keeps it that way. `ENS_CSP_REPORT_ONLY=true`
  sends `Content-Security-Policy-Report-Only` instead — an escape hatch for a page the tests
  missed, not a way to run.
- `Strict-Transport-Security: max-age=31536000; includeSubDomains` on secure requests,
  `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
  `Referrer-Policy: strict-origin-when-cross-origin` and
  `Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=()`.
- **The session cookie** is `HttpOnly` and `SameSite=Lax`, and `Secure` on any request that
  arrived over TLS. `ENS_COOKIE_SECURE=true` makes it `Secure` always, for a proxy that
  terminates TLS without saying so.
- **TLS 1.2 and 1.3 only, with FIPS-approved suites only**, when the application terminates
  TLS itself (`server.ssl.enabled-protocols`, `server.ssl.ciphers`). The suites are those of
  NIST SP 800-52 Rev. 2 with AES-GCM: `TLS_AES_256_GCM_SHA384` and `TLS_AES_128_GCM_SHA256` on
  TLS 1.3, and `TLS_ECDHE_{ECDSA,RSA}_WITH_AES_{256,128}_GCM_{SHA384,SHA256}` on TLS 1.2 - no
  ChaCha20, no CBC, no static RSA key exchange. `CERT_ALERT_TLS_CIPHERS` replaces the list
  (comma-separated IANA names) for a client that can use none of them. `TlsPolicyTest` holds
  the policy to real handshakes.
- **Behind a proxy that terminates TLS**, set `SERVER_FORWARD_HEADERS_STRATEGY=framework` so
  the application trusts its `X-Forwarded-*` headers: redirects stay on https, HSTS and the
  `Secure` cookie follow the browser's request, and the audit trail records the client's
  address rather than the proxy's. Only with a proxy in front that sets those headers itself.
  The Helm chart sets it whenever its ingress or an edge or reencrypt Route terminates TLS.
- **The release bundle** listens on `127.0.0.1` by default (`CERT_ALERT_BIND_ADDRESS`), for
  a TLS-terminating reverse proxy in front; to publish it to other hosts directly, turn TLS
  on and set `CERT_ALERT_BIND_ADDRESS=0.0.0.0`. With TLS off on any other address,
  `install.sh` refuses to start it unless `.env` sets `CERT_ALERT_ALLOW_PLAIN_HTTP=true`, and
  warns every time it does. See `deploy/release/README.md`, "Listening, and TLS".
- **PostgreSQL**: reach a database outside the bundle's internal network over TLS, with
  `sslmode=verify-full` in the JDBC URL (the chart's `database.params`).

### Upgrading: listening address, TLS suites and idle timeout

- **Release bundle.** Earlier bundles published on `0.0.0.0` and only warned about plain
  HTTP. An upgrade keeps that: when the copied `.env` predates this release, `install.sh`
  (and so `upgrade.sh`) adds `CERT_ALERT_BIND_ADDRESS=0.0.0.0` if it is not set, and
  `CERT_ALERT_ALLOW_PLAIN_HTTP=true` if TLS is off on an address other than `127.0.0.1`,
  under a comment saying why. Nothing else in `.env` changes. Move to a proxy on `127.0.0.1`
  or to TLS in the application, then delete `CERT_ALERT_ALLOW_PLAIN_HTTP`.
- **Idle timeout.** `ENS_SESSION_IDLE_TIMEOUT` now defaults to `15m` (was `30m`).
  `ENS_SESSION_IDLE_TIMEOUT=30m` (the chart's `session.idleTimeout`) restores the old one.
- **TLS suites.** With TLS terminated by the application, a client that could only use
  ChaCha20, CBC or RSA-key-exchange suites no longer connects. Every browser and JDK of the
  last decade negotiates AES-GCM; `CERT_ALERT_TLS_CIPHERS` is there for anything that does not.
  Nothing changes when a proxy or ingress terminates TLS.

## Session controls

| Setting | Default | |
|---|---|---|
| `ENS_SESSION_IDLE_TIMEOUT` | `15m` | A session nobody has used for this long ends. `SERVER_SERVLET_SESSION_TIMEOUT` still works as the same setting, and wins if both are set |
| `ENS_SESSION_ADMIN_IDLE_TIMEOUT` | same as idle | The same for an administrator's session (the STIG suggests `15m`) |
| `ENS_SESSION_ABSOLUTE_TIMEOUT` | `12h` | A session ends this long after sign-in, however busy |
| `ENS_SESSION_MAX_PER_USER` | `0` (no limit) | Sessions one person may hold at once. Signing in beyond it ends their **oldest** session - the earliest sign-in (the STIG suggests `3`) |

Durations are Spring durations: `15m`, `12h`, `90s`. An ended session goes back to the
sign-in page (`/login?expired`, which says so); an API call in one gets a 401. Each is
audited as `auth.session-expired` with its reason — `idle`, `absolute` or `concurrent` — and
the person and session it belonged to. A sign-out is `auth.logout`, not an expiry.

The limits apply however somebody signed in. A client certificate is checked on every
request rather than remembered by the session, so for its holder an ended session means a new
one on the next request — and, with the consent banner on, agreeing to it again. The first
signed-in request of every session also gives it a new id. The register of who holds which
sessions is in memory, like the sessions themselves: one instance, as the chart requires.

## Notice and consent banner

The DoD notice-and-consent banner (DISA STIG), shown before sign-in. Off by default.

| Setting | Default | |
|---|---|---|
| `ENS_CONSENT_BANNER_ENABLED` | `false` | Show the banner before sign-in |
| `ENS_CONSENT_BANNER_TITLE` | `U.S. Government (USG) Information System (IS) Notice and Consent` | |
| `ENS_CONSENT_BANNER_TEXT` | the standard DoD notice-and-consent text | Plain text: blank lines separate paragraphs, lines starting `- ` make a list. Shown as written, never as markup |
| `ENS_CONSENT_BANNER_FILE` | — | Read the text from a file instead; wins over `_TEXT`. Read at startup, and a file that cannot be read stops the application starting |

**Enforced on the server, not just shown.** While it is on, every request from a browser
session that has not pressed **I agree** is sent to `/consent` — before any sign-in filter
runs, so no sign-in of any kind succeeds first: not the form, not a client certificate. An
API call (`/api/**`) gets a 401 instead. The agreement is kept in the session, so signing out
shows the banner again before the next sign-in. It is audited as `auth.consent-acknowledged`
(category `authentication`, success, with the client's address).

Exempt, because they are not browser sessions or are what the banner is made of: `/consent`
itself, `/css/**`, `/js/**`, `/img/**`, `/webjars/**`, `/favicon.ico`, `/error`, the health
endpoints (`/actuator/health/**`, `/livez`, `/readyz`), `/actuator/prometheus` on the
management port, and the SIEM pull API (`/api/audit/**`, bearer tokens).

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

Being published is not enough on its own, because a directory goes on publishing what it
was given. A certificate the [revocation check](#revocation) has found revoked, or whose
validity dates do not cover the present moment, is refused even though the directory
publishes it, and the refusal is recorded as a failed `auth.login` whose reason says which
(`certificate revoked`, `certificate expired`, `certificate not yet valid`) with the
certificate's fingerprint — at `alert` severity for a revoked one. That is the cache's
answer; the live check at sign-in is below.

Setting `cert-alert.security.x509.require-known-certificate: false` falls back to matching
the `uid` and email address in the certificate's subject, and its `rfc822Name` alternative
names, against the uid and addresses the directory publishes for a person. That trusts a
name rather than a key, so it is off by default. The subject's common name is never
matched — any authority in the truststore can issue a certificate to any name — and a
certificate whose values name more than one person signs in nobody.

A certificate is checked on every request it is presented with, not remembered by the
session: removing it from the directory, or its authority revoking it, takes effect at once.

#### CAC/PIV: revocation at sign-in, issuers, and mapping (ENS baseline)

The same settings, with the same meanings, in every ENS product that signs in with
certificates.

| Setting | Default | |
|---|---|---|
| `ENS_X509_REVOCATION` | `ocsp-then-crl` | `off`, `ocsp`, `crl` or `ocsp-then-crl`: checked live, for every certificate presented, before it is matched to anybody |
| `ENS_X509_OCSP_RESPONDER_URL` | — | Ask this responder instead of the one the certificate names — a local OCSP relay on an air-gapped network |
| `ENS_X509_CRL_DIR` | — | A directory of CRL files (DER or PEM), re-read when they change. With it set, no distribution point is fetched over the network: the air-gapped option |
| `ENS_X509_REVOCATION_FAIL` | `open` while `ENS_X509_REVOCATION` is unset, otherwise `closed` | `closed`: when the status cannot be found, refuse. `open`: allow, and log a warning |
| `ENS_X509_REVOCATION_CACHE` | `10m` | How long a good or revoked answer is reused, per issuer and serial number. No answer is reused for 30 seconds |
| `ENS_X509_ALLOWED_ISSUERS` | — | Issuer DNs, separated by `;`. When set, a certificate from any other issuer is refused even if the trust store accepts it. Compared as names (RFC 4514), so spacing and case do not matter |
| `ENS_X509_USER_FILTER` | — | An LDAP filter that finds the holder of a certificate the directory does not publish, with `{upn}`, `{edipi}`, `{email}` and `{uuid}` filled in — e.g. `(uid={edipi})`. Used instead of the uid/address matching above, and only with `require-known-certificate: false` |

- **Revocation** needs the certificate's issuer: it is taken from the chain the browser
  presented, `ENS_X509_TRUSTED_CA_FILE`, `cert-alert.revocation.issuer-directory` or the TLS
  trust store, whichever has a certificate whose key verifies it. OCSP answers are checked
  by the platform's path validator; a CRL counts only if it is signed by the issuer and not
  past its next update. Fetches have 5 s to connect and 10 s to answer, follow redirects to
  http and https only, and never go to link-local or metadata addresses
  (`cert-alert.revocation.allowed-hosts` applies here too). A refusal is a failed
  `auth.login` with reason `certificate revoked` or `revocation unknown`.
- **The default is a live check that soft-fails.** With neither setting given, every
  certificate is checked over OCSP and then its CRL. One found revoked is refused. One that
  no responder or list answers for (the network is closed, the responder is down, the
  certificate names neither) signs in, and a warning naming it is logged:
  `Allowing client certificate '…' although no OCSP responder or CRL answered for it
  (soft-fail; …)`. A warning at startup says the check is soft-fail and how to change it.
  The STIG expects fail-closed, so set `ENS_X509_REVOCATION_FAIL=closed` once responders or
  `ENS_X509_CRL_DIR` answer reliably. On an air-gapped network, `ENS_X509_CRL_DIR` (or an
  `ENS_X509_OCSP_RESPONDER_URL` relay) gives the check something to answer with, and saves
  each sign-in waiting on fetches that cannot succeed. `ENS_X509_REVOCATION=off` still turns
  the check off, with a warning at startup that the STIG requires it. Choosing a mode
  yourself (`ocsp`, `crl` or `ocsp-then-crl`) keeps `closed` as the failure behaviour, as in
  earlier releases.
- **`{upn}`** is the SAN otherName UserPrincipalName; **`{edipi}`** the digits of a
  `1234567890@mil` UPN, or else the trailing number of a `LAST.FIRST.MI.1234567890` common
  name; **`{email}`** the rfc822Name SAN, or else the subject's emailAddress; **`{uuid}`** the
  `urn:uuid:` SAN URI (the PIV FASC-N UUID). Every value is LDAP-escaped where it is put in.
  A filter naming a value the certificate does not carry is not searched with, and a search
  that finds no entry or more than one — or one this application has not synced — signs
  nobody in. Published-certificate fingerprint matching stays the first and preferred way.
- **An issuer not on the allowlist** is refused with reason `issuer not allowed`.

#### Behind a TLS-terminating proxy (header mode)

By default (`ENS_X509_SOURCE=servlet`) a certificate is read from the TLS handshake the
application terminates itself. With `ENS_X509_SOURCE=header`, a proxy in front terminates
TLS, verifies the client certificate, and forwards it:

| Setting | Default | |
|---|---|---|
| `ENS_X509_SOURCE` | `servlet` | `servlet` or `header` |
| `ENS_X509_HEADER` | `X-SSL-Client-Cert` | The header, URL-encoded PEM as nginx's `$ssl_client_escaped_cert` sends it (ingress-nginx: `ssl-client-cert`) |
| `ENS_X509_TRUSTED_PROXIES` | `127.0.0.1/32,::1/128` | Addresses (CIDRs) the header is accepted from: the proxy's |
| `ENS_X509_PROXY_SECRET_FILE` / `ENS_X509_PROXY_SECRET` | — | At least 32 characters, which the proxy sends in `X-ENS-Proxy-Secret`; compared in constant time. The file wins |
| `ENS_X509_TRUSTED_CA_FILE` | — | PEM certificates of the CAs a forwarded certificate must chain to |

The header is believed only when the connection itself comes from a trusted proxy address
(never an address a forwarded-for header claims) **and** carries the secret, and the
certificate then chains to a CA in the file, is in date and is not restricted to some use
other than client authentication. Anything else is refused and recorded as a failed
`auth.login` (`certificate header not from a trusted proxy`, `certificate not trusted`). In
header mode a certificate from the application's own TLS handshake is ignored. **The
application refuses to start** in header mode without a secret of 32 characters or more,
without the CA file, or with `SERVER_FORWARD_HEADERS_STRATEGY=native` (use `framework`).

The proxy must set both headers itself and drop any copy a client sends. With nginx:

```nginx
ssl_verify_client optional;              # or on
ssl_client_certificate /etc/nginx/dod-cas.pem;
proxy_set_header X-SSL-Client-Cert  $ssl_client_escaped_cert;
proxy_set_header X-ENS-Proxy-Secret "<the secret>";
proxy_set_header X-Forwarded-Proto  $scheme;
```

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

### Single sign-on

OpenID Connect and SAML 2.0, alongside the certificate and the password. Both are off until
configured; with neither on, nothing about signing in changes. The sign-in page shows a
button for each one that is.

**Who it signs in.** The provider names the person by a claim (OIDC) or the NameID or an
attribute (SAML). That value is matched to a directory entry exactly as a password sign-in
finds one: as a **uid** (`match: UID`, the default) or as the **DN** of the entry
(`match: DN`), in the cached directory first and then in the directory itself under
`cert-alert.security.ldap.user-search-base` (or `user-dn-patterns`). Never as an address and
never as a name: an `email` claim is not used, and a uid that looks like an address must be
the entry's actual uid. Somebody the directory does not hold is refused: the provider says
who somebody is, the directory says whether they are somebody this application serves. Roles
come from the lists and [groups](#roles-from-groups) as for anybody else; a provider's groups
count only where `cert-alert.security.groups` names them.

**What is checked.**

- OIDC: the authorization code flow, always with PKCE (S256), whether or not there is a
  client secret. The ID token's signature against the provider's published keys
  (`jws-algorithm`, RS256 by default), its issuer (exactly `issuer-uri`), audience (the
  client id), authorized party, expiry and nonce, with `clock-skew` allowed. The provider's
  discovery document is read when first needed, so a provider that is down does not stop the
  application starting. No access or refresh token is kept.
- SAML: the response or its assertion must be signed by a key in the identity provider's
  metadata (unsigned is refused); the issuer must be that provider; the audience and
  destination this service provider; the conditions in date, with `clock-skew` allowed. Every
  response must answer (`InResponseTo`) a request this application sent and has not seen
  answered: requests wait in the database (`saml_authn_request`) for ten minutes and are used
  once, so a replayed or unsolicited (identity-provider-initiated) response is refused.

Sign-ins and refusals are recorded as `auth.login` with method `oidc` or `saml`, and the
refusal's reason. Signing out ends the session here; it does not sign the person out of the
provider (no single logout).

**The consent banner.** Starting a single sign-on is held at the banner like anything else.
The SAML answer is a cross-site `POST` that the session cookie (SameSite=Lax) does not come
with, so the agreement is carried in the stored request to the session the answer creates.

```yaml
cert-alert:
  sso:
    oidc:
      enabled: true
      display-name: Sign in with Entra ID
      issuer-uri: https://login.microsoftonline.com/<tenant-id>/v2.0
      client-id: <application (client) id>
      client-secret: <secret>          # empty for a public client: PKCE alone
      scopes: [openid, profile]
      username-claim: preferred_username
      match: UID                       # or DN
      groups-claim: groups
      jws-algorithm: RS256
      clock-skew: 2m
    saml:
      enabled: true
      display-name: Sign in with ADFS
      metadata-location: https://adfs.example.gov/FederationMetadata/2007-06/FederationMetadata.xml
      entity-id: "{baseUrl}/saml2/service-provider-metadata/{registrationId}"
      signing-key-location: file:/etc/cert-alert/saml/signing.key          # optional
      signing-certificate-location: file:/etc/cert-alert/saml/signing.crt
      username-attribute: ""           # empty: the NameID
      match: UID
      groups-attribute: http://schemas.microsoft.com/ws/2008/06/identity/claims/groups
      clock-skew: 2m
```

Every setting has an environment variable (`CERT_ALERT_SSO_OIDC_ISSUER_URI`, ...) in
`env.template` and a Helm value under `sso`. The addresses to give the provider:

| | OIDC | SAML |
|---|---|---|
| Redirect URI / assertion consumer | `https://<host>/login/oauth2/code/oidc` | `https://<host>/login/saml2/sso/saml` (HTTP-POST) |
| Service provider metadata | | `https://<host>/saml2/service-provider-metadata/saml` |
| Entity id | | `https://<host>/saml2/service-provider-metadata/saml` unless `entity-id` says otherwise |

Behind a proxy that terminates TLS, set `SERVER_FORWARD_HEADERS_STRATEGY=framework` so these
are built with the public `https://` address the provider expects.

#### Microsoft Entra ID (OIDC)

1. App registrations → New registration. Platform **Web**, redirect URI
   `https://<host>/login/oauth2/code/oidc`. Note the application (client) id and the
   directory (tenant) id; create a client secret.
2. Token configuration → add optional claim `preferred_username`; to use groups, add the
   groups claim (security groups). Entra ID sends group **object ids**, so list those in
   `cert-alert.security.groups`.
3. `issuer-uri: https://login.microsoftonline.com/<tenant-id>/v2.0`. `preferred_username`
   is the user principal name: use it with `match: UID` only where your directory's uids are
   the UPNs. Otherwise add a claim carrying the on-premises `samAccountName` or DN (directory
   extension attributes, or claims mapping) and name it in `username-claim`.

#### Okta (OIDC)

1. Applications → Create App Integration → OIDC, **Web Application**. Sign-in redirect URI
   `https://<host>/login/oauth2/code/oidc`; grant type Authorization Code (PKCE is used
   automatically).
2. `issuer-uri` is the authorization server's issuer, e.g. `https://<org>.okta.com/oauth2/default`.
3. For a uid, add a custom claim to the ID token (Security → API → Authorization Servers →
   Claims), e.g. `uid` = `user.login` or a profile attribute holding the directory uid, and set
   `username-claim: uid`. For groups, add a `groups` claim with a filter, and
   `groups-claim: groups`; Okta sends group names.

#### ADFS (SAML)

1. Add Relying Party Trust → import from
   `https://<host>/saml2/service-provider-metadata/saml` (or enter it by hand: SAML 2.0
   WebSSO, the assertion consumer above, the entity id above).
2. Claim issuance: send the LDAP attribute holding the uid (e.g. `SAM-Account-Name`) as
   **Name ID** (and leave `username-attribute` empty), or as an attribute you name in
   `username-attribute`. For a DN, send `distinguishedName` and set `match: DN`. For groups,
   send *Token-Groups - Qualified by Domain Name* or the group DNs, as the attribute in
   `groups-attribute`.
3. ADFS signs the assertion with its token-signing certificate, which is in its metadata
   (`https://<adfs>/FederationMetadata/2007-06/FederationMetadata.xml`). Where ADFS requires
   signed requests, give `signing-key-location` (PEM, PKCS#8) and `signing-certificate-location`.
4. For an ADFS this application cannot reach, save the metadata and use
   `metadata-location: file:/path/to/metadata.xml` (Helm: `sso.saml.metadataConfigMap`).

### Roles

| Role           | May                                                        |
|----------------|------------------------------------------------------------|
| `ROLE_USER`    | Read the search tables and the cached certificate detail   |
| `ROLE_AUDITOR` | Read everything an administrator reads - the administration page, the audit log and its export, the license screen - and change nothing at all |
| `ROLE_ADMIN`   | Also trigger the jobs, change settings and reach the management endpoints |

Everyone who authenticates gets `ROLE_USER`. `ROLE_ADMIN` comes from
`cert-alert.security.admin-identifiers`, matched against two things only: a person's uid,
and the DN of their entry. `ROLE_AUDITOR` comes from `cert-alert.security.auditor-identifiers`,
matched the same way. Either can also come from a group (below).

```yaml
cert-alert:
  security:
    admin-identifiers:
      - alice
      - uid=bob,ou=people,dc=example,dc=gov
```

An address is never matched, not even one the directory publishes. Plenty of directories
let people edit their own `mail`, and 389 Directory Server's default access controls do.
Matching on it would let anybody set their address to an administrator's and get the
role at their next sign-in. A common name, display name or preferred name is never
matched either: two people can share one, and the directory lets anybody be called
anything.

**An address in `admin-identifiers` is ignored.** Releases before this one also matched
the addresses the directory publishes, so an existing configuration may list
administrators that way. The application still starts, and every uid and DN in the list
still counts. Each address entry, though, grants nothing, and startup logs a warning for
it:

```
cert-alert.security.admin-identifiers lists 'alice@intelink.ic.gov', an e-mail address, and it is ignored: administrator rights match only a uid or the DN of a directory entry, because a directory may let people change their own mail. The directory cache has it as the address of uid 'alice' (uid=alice,ou=people,dc=example,dc=gov): list 'alice', or that DN, instead.
```

Replace each one with the uid or DN the warning names. If the list holds nothing but
addresses, a second warning says that nobody is an administrator until it is fixed. Where
a directory uses addresses as uids, list the DN, because an entry that looks like an
address is treated as one. An address entry still blocks anyone from adding that address
to their own entry (see [Addresses a person answers to](#addresses-a-person-answers-to)).

A DN has commas in it, so name one as a
block-style list item as above: not in a flow list (`[...]`), and not in the comma-separated
`CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS`, both of which split it at its commas. From the
environment, use indexed variables instead (`CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS_0`,
`CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS_1`, …), with `CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS` unset.

A password sign-in is resolved to the cached person at the DN it bound as, and to nobody
else. On a fresh deployment the cache is empty, so roles for someone signing in with a
password are resolved from the directory entry they just bound against: its DN and its uid,
never its addresses or the username typed. Otherwise nobody could trigger the first sync.

#### The auditor changes nothing

An auditor is refused - 403, recorded as `access.denied` - on every request that could
change something: anything but `GET`, `HEAD` and `OPTIONS`, whatever the endpoint, by one
rule ahead of all the others rather than one per endpoint, so an endpoint added later is
covered too. That includes what an ordinary user may do, such as editing contacts with
`contact-editors: AUTHENTICATED` or marking a notification read. The exceptions only read or
concern the session: the search tables (which `POST` their query), verifying the audit chain,
signing in and out, and agreeing to the consent banner. The pages hide what an auditor
cannot use. Somebody who is both an auditor and an administrator is an administrator.

#### Roles from groups

A role can come from a group as well as from the lists above: a directory group, from the
`memberOf` values of the person's entry (read with the service account when they sign in,
however they sign in), or a group a [single sign-on](#single-sign-on) provider says they are
in. Only the groups named here grant anything, so nobody becomes an administrator because a
provider puts them in a group called "Administrators":

```yaml
cert-alert:
  security:
    auditor-identifiers:
      - uid=inspector,ou=people,dc=example,dc=gov
    groups:
      admin:
        - cn=CertAlert Admins,ou=groups,dc=example,dc=gov   # a directory group's DN
        - 5b0f1c2e-1d0e-4c8b-9a77-2f2a6c0d9e11              # an Entra ID group's object id
      auditor:
        - cn=CertAlert Auditors,ou=groups,dc=example,dc=gov
      directory: true             # read memberOf at sign-in
      member-of-attribute: memberOf
      cache-for: 5m               # a certificate is checked on every request
```

Group names and DNs are compared without regard to case or spacing after commas. From the
environment, list them with indexed variables, e.g. `CERT_ALERT_SECURITY_GROUPS_ADMIN_0`.
OpenLDAP's `memberOf` overlay and 389 Directory Server's MemberOf plugin both maintain the
attribute; a directory without it grants nothing by group unless a provider says so. A group
change counts at the person's next sign-in, or within `cache-for` for a client certificate.

`/actuator/health` stays open so the thing can be monitored. Everything else requires
authentication; an unauthenticated API call gets a 401 rather than a login page, because a
table driven by fetch has no use for HTML in the response.

### Demo mode

A running copy anybody can look at and nobody can change — for showing the application to
people without issuing them a certificate or an account first.

```bash
./gradlew bootRun --args='--spring.profiles.active=demo'
```

That is the whole of it — nothing else has to be running, bar a license. The demo
generates a directory of invented people and servers, serves it from memory over LDAP, and
then indexes it the way it would index anyone's, so what a visitor sees is the application
working rather than a mock-up of it.

**A demo needs a license like any installation.** Download an evaluation license for
CertAlert from the ENS licensing portal. An unlicensed demo shows only the license screen:
sign in as `demo.admin` and upload it there — the one change a demo accepts, and only while
it has no license in full effect, so a visitor cannot swap a working one. The first sweep
runs as soon as it is installed. Or put it in place before starting: in the license
volume (`docker cp`, or `ENS_LICENSE_FILE` pointing at it when run from Gradle). No license
is ever committed to this repository; the tests sign their own with the portal's TEST key,
which a real build does not trust.

#### Signing in as each role

**The sign-in page is the demo.** What this application shows somebody depends on what they
have to do with the directory, so a demo that signed everybody in as one administrator
could only ever demonstrate one answer. Instead it lists the accounts the directory holds
and what each will see, and a visitor is each of them in turn:

| Role | Username | Sees |
|------|----------|------|
| Administrator | `demo.admin` | Everything, plus the administration page: the whole audit trail, the managed attributes, the controls that run the jobs |
| Project administrator | `demo.project` | Runs the Mission Systems project, so they manage the points of contact for every server in it — but not the administration page |
| Point of contact | `demo.contact` | Answers for a handful of servers: their own notifications, and the contact lists of the servers they are named on |
| Reader | `demo.reader` | Signed in and nothing more. The tables and the details pages, with none of the controls the others get |

All four carry the same password, `cert-alert.demo.password`, which defaults to `password`
and is printed beside each account on the page. One password rather than four because what
is being shown is the roles: four secrets to mistype would be four ways to fail at the only
step before the thing you came to look at. Picking a row fills the form and signs you in;
the account menu then offers **Sign out, and be somebody else**.

The passwords are checked the ordinary way, by binding to the directory as the person
signing in — here the demo's own. Nothing about the authentication is stubbed.

**The accounts are directory data, not a list in the code.** They live in
`src/main/resources/demo-accounts.ldif`, which the demo loads into its directory at
startup; the sign-in page then reads them back out of that directory by the attributes the
seed gives them — `demoRole`, `demoSummary` and `demoOrder`. Editing who the demo offers is
editing that one file. The reason is that a visitor signs in by *binding* as one of these
people, so the directory is what decides whether an account exists at all: a list in the
code saying otherwise would print usernames the directory has never heard of.

It follows that a demo pointed at a directory that was already running
(`generate-directory: false`) finds no accounts to offer, because those people are not in
it. The page says so rather than quietly dropping the tile — a tile that simply vanishes is
indistinguishable from one that was never deployed.

Three of the roles are what the directory makes them: the administrator is the account
the seed marks `demoRole: Administrator`, a point of contact is named in a `serverPOC`,
and the reader is deliberately neither.

**The seeded administrator is an administrator whatever `admin-identifiers` says.** That
setting is read from the environment, and a deployment carrying a value meant for a
different directory — the compose file names the generated 389ds directory's `alice` —
was silently demoting the demo's administrator to a reader: the sign-in page still called
them an administrator, and the administration page, the audit trail and the dry run all
refused them with no word as to why. So in demo mode the seed decides, as a union with
`admin-identifiers` rather than instead of it: anybody that setting names is still an
administrator, it just cannot take the role away from the seeded account. The startup log
says which it was. Running a project is not something a directory entry can carry —
projects are this application's own data — so the demo creates one at startup and puts the
project administrator in charge of it. Without that, signing in as them would show exactly
what the reader shows and the page would be describing a role the demo does not have.

It serves plain HTTP, on `http://localhost:8080`, even where `CERT_ALERT_TLS_ENABLED` is
set. TLS is here to carry X.509 client certificates, and a demo has nothing for one to
authenticate; it also needs a keystore, which a demo does not carry — so a demo that
inherited TLS from its environment would fail to start for want of one. Put a demo behind
TLS deliberately with `SERVER_SSL_ENABLED=true` and the keystore variables, or terminate it
in front.

#### Nothing changes, whoever you are

**Read-only is a rule about the request, not about the buttons** — every method that is not
a `GET`, `HEAD` or `OPTIONS` is refused before any controller sees it, so a demo's safety
does not depend on the UI happening not to offer something. That holds for the
administrator too, which is the case worth stating: a rule written about roles would have
let the one account with the most to see through. The buttons stay where they are, for
every role, and pressing one says so:

```
HTTP/1.1 403
Content-Type: application/problem+json

{"title":"Read-only demo","status":403,
 "detail":"This is a read-only demo: nothing here can be changed.","instance":"/api/v1/sync"}
```

By method rather than by a list of paths, so an endpoint added next month is refused
without anybody remembering to add it. Two things are exempt, and both write nothing: the
search tables' `/api/v1/datatables/**` endpoints, which are `POST`s only because that is
how DataTables sends its paging and filters; and **Dry run**, which builds every message
the round-up would send, reports what would have gone out, and saves none of it. The real
round-up beside it stays refused, so whether that endpoint is allowed depends on
`dryRun=true` being on the request rather than on its path. The rehearsal is an
administrator's, as it is anywhere else — who would be written to is their business.

Because every write is refused anyway, what signing in decides is what you may **read** —
a much shorter list, and the only place the roles differ. Refused a page rather than a
button, a browser gets the application's own 403 page saying which role it is for; a script
gets the problem detail above.

#### The directory it shows

Generated at startup, never shipped as a fixture. Certificates are minted there and then,
with every window measured from the moment the process started, so "expires in nine days"
still means nine days on an image built last spring — which is the one thing a demo of an
expiry tracker cannot get wrong. They are real X.509, signed by a throwaway authority that
goes with the process; nothing trusts it and nothing authenticates against it.

What it holds is chosen to exercise the application rather than to look tidy:

- **The accounts the seed defines**, loaded from `demo-accounts.ldif` rather than
  generated, under fixed uids so what the sign-in page prints can be typed. All hold
  credentials expiring shortly, so whichever a visitor picks, the pages that are about the
  person reading them are not empty in a way that looks like a fault. Two of them are
  points of contact for several servers each and two deliberately are not, which is the
  difference the roles describe. Certificates are minted for them at startup like everybody
  else's, because those are the one thing a seed cannot carry.
- **Certificates in every state**: good for years, inside the warning window, inside the
  critical one, lapsed weeks ago, and entries publishing none at all. Dealt from a
  proportioned deck rather than drawn at random, because sixteen servers drawing
  independently regularly came up with nothing expired — a demo quietly not showing one of
  the things it exists to show. Most of it is healthy, so it reads as a busy estate rather
  than a broken fixture.
- **Pairs**: people hold a signing certificate and an encryption one, and some hold a pair
  straddling two issuances, which is the ordinary way to end up half expired.
- **Points of contact written both ways round**, as an address on some servers and as a
  person's name on others, because the specification defines `serverPOC` as a name and
  real directories do both.
- **A wildcard name** on one server, so the risky-name flag has something to flag.

Seeded, so a demo restarted an hour later is the same demo; only the dates move.

Three things the demo does for itself, none of them a visitor's doing. It sweeps the
directory once at startup (or as soon as a license is installed, if it started without
one), because otherwise the tables stay blank until the small hours.
It then runs the expiry round-up, because notifications are written when a sweep sees a
certificate *change* state and on a first sweep nothing has changed — everything is simply
new — which would leave the notifications page empty beside tables full of things expiring
this week. And the profile switches off pruning, certificate cleanup and outbound email,
because a visitor cannot start those but the schedules would.

The database is in-memory and goes with the process, so every start is a fresh directory
and a fresh index of it. A demo that kept its cache in a file would come back tomorrow
still holding yesterday's dates while the directory beside it had been generated afresh.

To demonstrate against a directory that is already running instead — the one in the
compose file, or a generated LDIF loaded into a real server — set
`cert-alert.demo.generate-directory: false` and point `spring.ldap.urls` at it.

| Setting                                  | Default         |                                           |
|------------------------------------------|-----------------|-------------------------------------------|
| `cert-alert.demo.enabled`                | `false`         | The whole of it. Replaces the ordinary security configuration rather than relaxing it, so the two are never half-applied |
| `cert-alert.demo.password`               | `password`      | What every demo account carries, printed beside it on the sign-in page |
| `cert-alert.demo.generate-directory`     | `true`          | Whether the demo brings a directory of its own. Off to point it at one already running |
| `cert-alert.demo.people` / `.servers`    | `24` / `16`     | How much to generate                      |
| `cert-alert.demo.seed`                   | `20260101`      | Fixes the choices, so a restart is the same demo. The dates always move with it |

> **Never point a demo at a real directory.** It prints working credentials on its front
> page, and the account they open reads every entry the directory holds. That is a
> reasonable thing to do with invented entries on an instance you can throw away, and
> nothing else. `cert-alert.demo.enabled` is not a way to run this application for real,
> any more than `cert-alert.security.enabled: false` is.

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

Hibernate then renders `nulls last` into the SQL and both databases agree, so the first row
is the soonest thing to run out.

Lapsed entries are out of the way to begin with on both tables — they are what has already
gone wrong rather than what is about to — but the two say so differently. On people it is
the **Show expired** switch, off by default. On servers it is the first option of the
Certificates list, **Hide expired**, which is the one selected when the page opens.

Servers had the switch too and lost it. What to do about expired entries is the first thing
that list asks, so a switch beside it hiding half the directory while the list read "Any"
was two controls disagreeing about one question. One control now answers it, and the
answer is visible rather than implied: **Any, expired included** reaches them, and a link
can opt out of the default with `?certState=` — which is how the metrics page sends somebody
to every server holding a risky name, lapsed ones among them.

Both tables carry the same filters, and both take the two expiry dates an entry has:

| Filter | Parameter | What it asks |
|---|---|---|
| Certificates | `certificateStatus`, `expired`, `hasCertificates` | the entry's rolled-up state — the worst of the certificates it still stands on |
| Expiring within | `expiringWithinDays` | how soon the **next** one runs out |
| Last certificate expires | `latestExpiryFrom`, `latestExpiryTo` | the day the **last** of them runs out, as a range of whole days, either end optional |
| Point of contact | `poc` | on servers, a contact whose name or address contains this; on people, somebody a `serverPOC` could name by it |
| Project | `projectId` | the entries in one project |
| Only mine (servers) | `pocUserId` | the servers the person reading the page is a point of contact for |

**Only mine** is on the servers page alone, and it is the one filter that cannot be written
down in advance: it resolves the signed-in account to its directory entry and asks for the
servers that entry is a point of contact for — published by the directory or added here,
the same set that decides who hears about a certificate expiring. It is the switch form of
the filter a person's row already links to, so the two share one state: following the link
for yourself arrives with the switch on rather than with a badge carrying your own name.
An account the directory has no entry for — a service account, or a name the directory
holds twice — is not offered the switch, since it could only ever show an empty table.

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

**The contacts column shows one and counts the rest.** A server with a team on it publishes
a dozen addresses, and a cell that printed them all took the width of the table and pushed
the certificate columns off the side of the page. So the column shows the first contact, a
`+n more` count that opens the row where the full list lives, and `+n added` where somebody
has added contacts here — two counts, because they answer different questions. Hovering the
count lists everyone without going anywhere, and the point-of-contact filter still searches
every address, including the ones the cell does not show.

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

The directory publishes up to five addresses per person — `mail`, `icEmail`,
`internetEmail`, `niprnetEmail`, `siprnetEmail` — and all five are indexed. Those are the
ones the IC FSD schema defines, and a real directory usually carries more: an agency's own
`alternateMail`, a mail-system attribute that predates the schema, a team address kept on
the person who owns it. Name them and they are read too:

```yaml
cert-alert:
  ldap:
    user:
      additional-email-attributes: [alternateMail, legacyMail]
```

An address found in one is indexed like any other the person answers to, so a `serverPOC`
written with it resolves to them and they hear about that server's certificates. Values are
read whole and split on commas — an attribute can hold several values, and a directory
filled in through a form often holds several addresses inside one of them. Anything that is
not an address is dropped and logged against the entry it came from: these attributes are
declared as places addresses live, so a display name in one is bad data rather than a second
kind of value, and indexing it would widen what a `serverPOC` matches to something nothing
can be sent to.

Naming one of them in `email-precedence` makes it the primary address, the one the tables
show. They are held on the entry rather than folded straight into the identifier set,
because that set is rebuilt from what the entry holds — by a sweep, and again whenever
somebody edits the addresses added here — and anything only the directory knows has to
survive the second of those. They appear under *Addresses this person answers to* on their
page; the *Addresses* card above it lists the five schema attributes by name, since only
those have a column of their own.

A server's `serverPOC`, though, is written by whoever runs the server, and they write what
they use: an old address, a role address, or the team's distribution list. A server named
after a list has no contact at all as far as the directory is concerned.

So a person's details page carries **addresses added here** alongside the published ones.
An address added there joins the set a `serverPOC` is matched against, and the servers
named after it become theirs immediately — not at the next sweep.

That is all it does. An address added here never makes anybody an administrator:
`admin-identifiers` is matched only against a uid or a DN. And an address
`admin-identifiers` names cannot be added at all — the request is refused with a 400 saying
so — because where `contact-editors: AUTHENTICATED` lets anyone signed in add addresses,
that would be somebody naming themselves after an administrator. One added before this was
refused stays, still binds servers, and grants nothing.

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

All of that is waiting, so it is done several at a time — `cert-alert.revocation.workers`,
eight by default. Asked one after another at a few hundred milliseconds a round trip, a
directory of any size does not finish overnight, and against a responder that has stopped
answering each certificate waits out the timeout before the next one begins. Responders are
asked with the same `connect-timeout` and `read-timeout` as the lists, through the same
client; the platform's own OCSP client, and its process-wide timeout property, are not used.

Only the asking happens off the job's own thread. Recording what came back, telling people
and writing the audit trail all happen in order on the thread that owns the transaction, and
a distribution point is still fetched once however many workers want it at the same moment —
whoever asks first downloads, the rest wait for that answer.

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

**What a certificate can make this application fetch.** Every distribution point and
responder address comes out of a certificate, and a certificate is something somebody else
wrote — so every download is held to limits, and to a list of places it may not go:

| Setting | Default | |
|---|---|---|
| `cert-alert.revocation.max-crl-bytes` | `16777216` | A list over this is dropped as it downloads — counted as it arrives, never read whole and measured afterwards. A list in a directory is held to it by the largest message the connection accepts |
| `cert-alert.revocation.connect-timeout` / `read-timeout` | `10s` / `30s` | Each wait |
| `cert-alert.revocation.max-fetch-time` | `60s` | The whole of one download, redirects included, so a server answering a byte at a time cannot hold a worker |
| `cert-alert.revocation.allowed-hosts` (`CERT_ALERT_REVOCATION_ALLOWED_HOSTS`) | empty | When set, the only hosts fetched from: names, `*.example.gov` for a domain and everything under it, or addresses and ranges such as `10.20.0.0/16` |

Whatever is configured, **link-local and cloud metadata addresses are never fetched from**
(169.254.0.0/16 — which includes 169.254.169.254 — fe80::/10, fd00:ec2::254, 100.100.100.200,
0.0.0.0/8), and **loopback only when its host is named in `allowed-hosts`**. The hosts of
`default-ocsp-url` and `default-crl-url` are always allowed, loopback included: they are a
decision somebody made, not one a certificate made for them. A redirect is followed — at most
three — only to http or https, and every hop is checked the same way; an OCSP request, which
is a POST, follows none. A refused address is logged as a warning naming the certificate's
URL and counts as *Unknown*, never as an answer.

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

Every one of these records is also an event in the application-wide audit trail, in the
shared ENS format — see [Audit logging](#audit-logging), which covers sign-ins, refusals,
settings, the license and job runs as well, and how the trail reaches a SIEM.

Nothing is ever pruned unless asked: `ENS_AUDIT_RETENTION_DAYS` is `0` (keep forever) by
default. The older `cert-alert.audit.retention.enabled` / `.after` still work while it is 0
(they are mapped onto it), now on a daily schedule. `cert-alert.audit.enabled: false` stops
these entry records being written without deleting or hiding what is already there;
security events are recorded regardless.

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

**A person's pair is one notification.** PKI for people issues two certificates at a time —
one that signs, one that is encrypted to — to the same name, within moments of each other.
They expire within moments of each other too, and renewing means replacing both. So the
round-up counts credentials rather than certificates: the pair is one line, with one date and
both serial numbers on it, and a person who holds a pair and looks after a server has two
things to do rather than three. The transition alerts do the same — both halves crossing into
a bad state is one notification, not two that differ only in serial number.

Two certificates are one credential when they carry the same subject, one signs and the other
is encrypted to, and they were issued within `cert-alert.credentials.pair-window` of each
other (a week by default) — which is the question "was this one renewal or two" with some
slack on it. Anything else stands alone: a server's certificate does both jobs and is a
credential by itself, and so is a half whose partner was never published. Where a pair
straddles two issuances — one half renewed, one not — the renewed half drops off and the
other is still reported, which is exactly the case worth hearing about.

The date on the line is the **sooner** of the two. A pair is only as good as its first half to
lapse: renewing one and not the other leaves somebody able to sign and not to read.

Recipients are resolved through everything this application knows about who is responsible:
a person's own certificate is theirs, and a server's is its points of contact's — the
directory's `serverPOC` values resolved to people, which now includes group addresses, plus
the contacts added here. An address nobody has claimed is still a recipient by email; it
just has nobody to show a notification to.

Nobody hears the same thing twice. A transition alert is not raised again for the same
certificate in the same state inside `cert-alert.notifications.repeat-after` (a week by
default) — a nightly sweep and a certificate that stays expiring for a month would otherwise
mean thirty notifications.

The round-up needs the same guarantee for a different reason, and gets it a different way.
It reports what is expiring **now**, so it is not an event that happened but a standing
statement of what somebody has to do, and each person has **one** of them. A run that finds
the same thing leaves that message exactly as it is — read stays read, and the page does not
grow a copy of what is already on it. A run that finds something different updates the
wording and it reads as unread again, because something is expiring that was not before, or
something has been renewed. When everything it named has been dealt with, it goes: a report
of the current state that outlives the state sends somebody looking for work that is already
done.

The email is unchanged by any of that — the round-up is a daily report and goes out daily.
It is the page that holds one message per person rather than one per run.

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

**What one run reads.** The certificates about to expire, and — with
`cert-alert.notifications.digest.include-expired`, on by default — those already expired,
are read separately, the ones about to expire first. However many old certificates a
directory goes on publishing, they cannot push next week's out of the round-up.

- **A page at a time.** Each read is paged
  (`cert-alert.notifications.digest.page-size`, 1,000 per query) by expiry date and id, so a
  page costs the same however deep the read goes. Each read stops at
  `cert-alert.notifications.digest.scan-limit` (50,000 per kind). That limit bounds one
  run's memory. It is not a target.
- **A bounded look-back.** Only certificates that expired in the last
  `cert-alert.notifications.digest.expired-look-back` (`CERT_ALERT_NOTIFICATIONS_DIGEST_EXPIRED_LOOK_BACK`,
  `90d`) are reported as expired. One that lapsed longer ago is either finished with or has
  been reported every day since. `0` removes the bound.
- **Renewals left out by the query.** A certificate whose entry has published a later one
  with the same subject and key usage, not itself expired, has been renewed. The query skips
  it, as it skips an expired certificate on an entry that holds a current one, so neither
  uses up the limit. The same renewal rule then runs again in the application, which also
  pairs a person's signing and encryption certificates.
- **Truncation is reported.** Of what remains, a capped read keeps the soonest to expire,
  or the most recently expired. A run that reaches the limit logs a warning naming which of
  the two it was. It counts the run in `ens_certalert_digest_truncated_total`, so you can
  alert on it, and leaves earlier round-ups in place rather than clearing ones it may simply
  not have reached.

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

#### When the mail server is down

Nothing is sent from inside a database transaction. A round-up's messages, and the alert
channel's, are written to an outbox table (`outbound_email`) in the same transaction as the
notifications or the sweep that produced them, and sent once that has committed — the
round-up straight away, so the page can say what went; an alert by a background thread a
moment later. A mail server that is slow therefore holds no transaction open, and one that is
down loses nothing:

- a message that cannot be sent stays queued and is tried again a minute later, then two,
  four and so on up to an hour apart (`cert-alert.mail.outbox.retry-initial` / `retry-max`),
  each failure logged as a warning with the mail server's reason;
- a newer round-up to the same address replaces one still waiting, so a mail server that comes
  back after a weekend delivers today's round-up rather than three;
- after three days (`give-up-after`) a message is given up on, with an error in the log;
- a notification is marked emailed when its message actually goes, not when it is queued.

A round-up run while the mail server is down says so on the notifications page — "could not
be sent and are queued to try again", with the reason — rather than reporting them as sent.
An alert is recorded as `alert.sent` once it is safely queued.

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

**A message that could not be built says so, in red, at the top of the card** — who it was
for and what the failure said. Templates of a deployment's own are the ordinary way for that
to happen, since the wording is yours to edit and an expression with a typo in it throws
where it is rendered. That used to leave a line in the server log and nothing on the page:
"nothing would be sent", with a note blaming whichever unrelated thing came next down the
list. Surfacing exactly this is what a rehearsal is for.

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

`/admin` carries counts of the audit trail — how much there is, how much of it is from the
last day and the last week, how many distinct people and jobs appear in it — and the span it
covers, which together say whether anything is running at all. The trail itself is on the
**audit log page**, `/admin/audit` (see [Audit logging](#audit-logging)), which filters by
time, category, action, outcome, severity, actor, target, source address and free text.

An entry's own history stays open to anyone signed in: a person looking at a server can see
what has been done to it, and an audit trail nobody may read holds nobody to account. The
whole trail at once is an administrator's, because read end to end it says who has been
here, what they touched and when. Asking `/api/v1/datatables/audit` without a subject is
that same request, so it checks for itself rather than trusting the page it is usually
reached through.

Records outlive what they describe. After a prune the DN an entry's records carry is what
is left, and the trail still says the entry was pruned.

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
| `GET`  | `/api/v1/jobs/{id}`                 | How a job started by hand is going (the job endpoints answer 202 with it) |
| `GET`  | `/api/v1/jobs`                      | Jobs started by hand, newest first       |
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
its own interval — by a lock in the database (`shedlock`), which a run started by hand takes
too: pressing "Sync directory" while the nightly sweep is running, on this replica or another,
does not start a second sweep. A replica that dies holding a lock releases it after
`cert-alert.cluster.lock-at-most-for` (10 minutes; renewed while the job runs, however long).
Every run is recorded in `sync_run`, readable at `GET /api/v1/sync/runs`.

### Starting a job by hand

`POST /api/v1/sync` and the other job endpoints (`/sync/users`, `/sync/servers`,
`/sync/refresh`, `/sync/prune`, `/sync/certificate-cleanup`, `/changelog/poll`,
`/revocation/check`, and `/notifications/digest` without `dryRun`) answer **202 Accepted** at
once with a run, and the job runs in the background:

```json
{"id":"7c0e…","job":"directory sync","state":"QUEUED","finished":false,
 "requestedAt":"…","statusUrl":"/api/v1/jobs/7c0e…"}
```

`GET /api/v1/jobs/{id}` (also in the `Location` header) says how it is going - `QUEUED`,
`RUNNING`, `SUCCEEDED` with `result` (what the endpoint used to answer with), `FAILED` with
`error`, or `SKIPPED` when the same job was already running. `GET /api/v1/jobs` lists recent
runs. The pages ask until the run has finished. Runs are kept in `job_run` for
`cert-alert.cluster.run-retention` (7 days), so whichever replica a request reaches can answer;
a run whose replica stops is marked `FAILED` within `stale-run-after` (5 minutes). Each is
audited as `job.requested`, with the run id, when it is asked for.

## High availability

More than one replica is supported. What makes it safe:

- **Jobs.** Each scheduled job, and the same job started by hand, takes a lock in the database
  (`shedlock`), so it runs on one replica at a time. Every replica's schedule fires; the first
  to take the lock runs the job, the others log that it is already running. The same goes for
  the changelog connector's polls, the mail outbox's sends, each audit output's deliveries,
  audit retention and the expired-session sweep.
- **Sessions.** With `cert-alert.session.store=jdbc` - the default in the postgres profile,
  which the chart and the release bundle use - sessions live in the database
  (`spring_session`). Any replica serves any request, and a restart or rolling update signs
  nobody out. The session controls (idle, absolute, how many per person) are enforced from
  the database too, and idle sessions are still recorded as `auth.session-expired`.
- **Jobs started by hand** are recorded in `job_run`, so the page can ask any replica how one
  is going.
- **SAML sign-in requests** wait in the database (`saml_authn_request`), so the identity
  provider's answer can reach any replica. OIDC keeps its state in the session.

What is still each replica's own, and why that is acceptable:

| State | Per replica | Effect with two |
|---|---|---|
| Repeated sign-in failures for one name (the `ALERT` after five) | counted per replica | an attacker spread across replicas needs up to five per replica before the alert |
| Collapsed `access.denied` and certificate sign-in failures | one event a minute per replica | up to one per replica a minute |
| License mode changes (`license.mode-changed`) | each replica notices its own | recorded once by each replica that notices |
| `ens_certalert_job_last_success_seconds` | set where the job ran | the PrometheusRule takes the `max` across replicas |
| Directory groups, CRLs, sign-in revocation answers | caches | each replica fills its own |

**The license volume.** The vendored license library keeps this installation's id and the
license revisions it has seen in files on the license volume (`/var/lib/ens`). Every replica
must see the same files, or each would have an installation id of its own, so above one
replica the chart requires a ReadWriteMany volume and `persistence.license.readWriteMany: true`
to say so. A shared store for that state is coming in the license library; until then, see
[the chart's README](deploy/helm/cert-alert/README.md#high-availability).

**Helm.** `replicaCount: 2` (with the license volume as above) gives a `RollingUpdate`
strategy with `maxUnavailable: 0` and `minReadySeconds`, a PodDisruptionBudget
(`minAvailable: 1`), a preference for spreading the pods across nodes, and a short `preStop`
pause so a pod leaves the Service before it stops taking requests. Readiness is the database
and the audit log; a pod is not sent traffic until it is ready. With one replica nothing
changes: `Recreate`, no budget.

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

## Audit logging

CertAlert keeps a complete, tamper-evident audit trail in the format every ENS product shares,
**`ens-audit/1`**: one JSON object per event, numbered by `seq` without gaps and chained by
SHA-256, so an edited or deleted record shows. Administrators search, inspect, export and
verify it at **`/admin/audit`**; Splunk or any SIEM receives it over HEC or syslog, pulls it
from an API, or reads it from stdout or a file. The full format and the Splunk set-up are on
the documentation site: [audit-logging.md](https://github.com/winllc/ens-apps-documentation/blob/master/docs/audit-logging.md).

```json
{"schema":"ens-audit/1","id":"…","seq":1234,"time":"2026-10-16T02:10:11.123Z","product":"certalert",
 "version":"1.4.0","host":"…","installationId":"…","category":"authentication","action":"auth.login",
 "outcome":"failure","severity":"warning","actor":{"type":"anonymous","id":"jdoe","name":null,"roles":[]},
 "source":{"ip":"10.1.2.3","userAgent":"…"},"target":null,"reason":"bad credentials",
 "details":{"method":"ldap"},"requestId":"…","session":"…","prevHash":"…","hash":"…"}
```

### What is recorded

| Category | Actions |
|---|---|
| authentication | `auth.login` (success and failure; `details.method` `x509` or `ldap`, the attempted name as an `anonymous` actor on failure, `alert` severity after repeated failures for one name; a published client certificate refused as revoked, expired or not yet valid carries that as its reason and `details.fingerprint`, and a revoked one is `alert`), `auth.logout` |
| authorization | `access.denied` for every 403: a role refused, `license_required`, `license_read_only`, `feature_not_licensed`, a CSRF failure — with method, path and reason code; identical refusals within 60 s are one event with `details.count` |
| account | `project.admin-added`, `project.admin-removed` (the one role this application grants) |
| configuration | `settings.changed` (the round-up's lead days, `<field>.old`/`.new`), `server-attribute.defined`, `.changed`, `.retired` |
| license | `license.installed`, `license.rejected` (with the reason), `license.mode-changed` (FULL / READ_ONLY / UNLICENSED, checked at startup, on every request and every minute), `license.limit-exceeded` (at most hourly, with the count refused) |
| data | every entry change, as it always was: `entry.discovered`, `entry.pruned`, `certificate.cached`, `.removed`, `.deleted` (cleanup), `.revoked`, `.status-changed`, `contact.added`/`.removed`, `address.added`/`.removed`, `attribute.set`/`.cleared`, `project.joined`/`.left`, `endpoint.probed`; and `project.created`, `.updated`, `.deleted` |
| system | `system.started`, `system.stopped`; `job.completed` / `job.failed` once per run of every job — user and server sweeps, the expiry refresh, changelog sync (each poll that applied changes, each run of failures), the expiry digest and notification trim, revocation checks, certificate cleanup, prune — with counts and duration, scheduled or started from the UI; `job.skipped` once each time a job stands down for the license; `alert.sent` / `alert.failed` |
| audit | `audit.exported`, `audit.verified`, `audit.token-created`, `audit.token-revoked`, `audit.pruned`, `audit.delivery-failed`, `audit.delivery-recovered` |

Secrets are never recorded: passwords, tokens, keys, license text, session ids and codes are
written as `"[redacted]"`. `session` is the first 16 hex characters of the SHA-256 of the
session id. `source.ip` is the request's remote address; this application does not trust
`X-Forwarded-For` unless `server.forward-headers-strategy` is set.

### Where it is kept

In the database, in `audit_event` — the same table the entry histories have always lived in,
migrated by Flyway (`V18`); the records from before are numbered and chained at first
startup, oldest first, keeping their times, with `details.migrated=true`. Each event is
written in the transaction that caused it, so a change and its record commit together; if
the record cannot be written, a security change fails rather than happening unaudited.
Writers are serialised by a row lock on the single `audit_chain_head` row, taken just before
commit, which holds across instances sharing one database. Nothing in the application
updates or deletes an event except retention pruning.

### The audit log page

`/admin/audit`, for administrators, and reachable whatever the license says — unlicensed and
read-only included, since the audit trail is not a licensed feature:

- the log, newest first and paged on the server, filtered by time, category, action,
  outcome, severity, actor, target, source IP and free text over the JSON; a row opens the
  event in full, with its formatted JSON, seq and hash;
- **Export** of the current filter (up to 100,000 events) as CSV or NDJSON, itself recorded
  as `audit.exported`;
- **Verify integrity**, which recomputes the chain and reports intact, broken at seq N, or the
  anchor (the first retained seq after pruning, whose `prevHash` is taken as given);
- **Delivery**: per output, whether it is on, the last seq delivered and when, the backlog
  and the last error;
- **SIEM API tokens**: create (shown once, with a copy button), list, revoke;
- **Retention**, as configured.

### Outputs and settings

| Variable | Default | |
|---|---|---|
| `ENS_AUDIT_STDOUT` | `true` | each event as one raw JSON line on stdout (logger `ens.audit`, its own appender, no prefix) |
| `ENS_AUDIT_FILE` | empty (off) | a JSONL file, e.g. `/var/log/ens/certalert/audit.jsonl`, rotated daily or at 100 MB |
| `ENS_AUDIT_FILE_KEEP` | `30` | rotated files kept |
| `ENS_AUDIT_RETENTION_DAYS` | `0` (forever) | prune events older than this, daily, once every forwarding output has delivered them; never the newest. `cert-alert.audit.retention.*` still applies while this is 0 |
| `ENS_AUDIT_SPLUNK_URL` | empty (off) | Splunk HEC, e.g. `https://splunk.example:8088`; posts batches of up to 100 to `/services/collector/event` |
| `ENS_AUDIT_SPLUNK_TOKEN` / `ENS_AUDIT_SPLUNK_TOKEN_FILE` | | the HEC token, or a file holding it |
| `ENS_AUDIT_SPLUNK_INDEX` | empty | |
| `ENS_AUDIT_SPLUNK_SOURCETYPE` | `ens:audit` | |
| `ENS_AUDIT_SPLUNK_INSECURE` | `false` | skip TLS verification — test setups only; warned about at startup |
| `ENS_AUDIT_SYSLOG_HOST` | empty (off) | RFC 5424 syslog: APP-NAME `certalert`, MSGID the action, the JSON line as the message |
| `ENS_AUDIT_SYSLOG_PORT` | 514, or 6514 for tls | |
| `ENS_AUDIT_SYSLOG_PROTOCOL` | `tcp` | `udp`, `tcp` or `tls` (octet-counted framing on tcp and tls) |
| `ENS_AUDIT_SYSLOG_FACILITY` | `local0` | |

HEC and syslog are delivered in the background from the database by a cursor kept in
`audit_delivery`: at-least-once (Splunk can de-duplicate on `id`), resumed after a restart,
retried with backoff up to five minutes, and never slowing a request. An output failing for
fifteen minutes records `audit.delivery-failed`, and `audit.delivery-recovered` when it comes
back. Stdout and the file are written just after each commit. Startup logs one line per
output saying whether it is on; the token is never logged. TLS trusts the JVM's trust store.

The release bundle passes every `ENS_AUDIT_*` variable through (see `env.template`) and mounts
the `ens-audit` volume at `/var/log/ens`, which the image creates writable by its user and by
group 0 for OpenShift.

### Getting it into Splunk

Any one of these:

- **HEC** — set `ENS_AUDIT_SPLUNK_URL` and `ENS_AUDIT_SPLUNK_TOKEN`. Events arrive with
  `sourcetype=ens:audit`, `source=certalert`.
- **Universal Forwarder** — monitor the file (`ENS_AUDIT_FILE`, in the `ens-audit` volume) or
  the container's Docker logs, with `sourcetype=ens:audit`. Each line is one event.
- **Pull API** — create a token on `/admin/audit`, then
  `GET /api/audit/events?after=<seq>&limit=<1..1000>` with `Authorization: Bearer <token>`.
  NDJSON by default, one event per line in seq order; with `Accept: application/json`,
  `{"events":[…],"next":<seq>}`. Ask again with `after` set to the last seq you have. It needs
  no session or CSRF token, works while the application is unlicensed, and is not itself
  audited (the token's last use is recorded instead).

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

Waiting on the directory and the mail server. Without these, a directory or mail server that
accepts a connection and then says nothing holds whatever was talking to it for good:

| Environment variable | Default | Purpose |
|---|---|---|
| `CERT_ALERT_LDAP_CONNECT_TIMEOUT_MS` | `10000` | Reaching the directory (every connection: the sweeps, the changelog, sign-in) |
| `CERT_ALERT_LDAP_READ_TIMEOUT_MS` | `60000` | Each answer from it - one page of a sweep, one bind. Raise it for a directory that takes longer than a minute to return one page |
| `CERT_ALERT_MAIL_CONNECT_TIMEOUT_MS` | `30000` | Reaching the mail server (`mail.smtp.connectiontimeout`, and `smtps`) |
| `CERT_ALERT_MAIL_READ_TIMEOUT_MS` | `30000` | Each answer from it (`mail.smtp.timeout`) |
| `CERT_ALERT_MAIL_WRITE_TIMEOUT_MS` | `30000` | Each write to it (`mail.smtp.writetimeout`) |

They are `spring.ldap.base-environment` and `spring.mail.properties` entries, so either map can
also be set directly.

Email that cannot be sent yet, under `cert-alert.mail.outbox` (see [When the mail server is
down](#when-the-mail-server-is-down)):

| Property | Default | Purpose |
|---|---|---|
| `poll-interval` | `30s` | How often waiting mail is looked at |
| `retry-initial` | `1m` | Wait after the first failure; it doubles after each one after |
| `retry-max` | `1h` | Longest wait between two attempts |
| `give-up-after` | `3d` | How long a message keeps being tried before it is given up on and an error logged |
| `keep` | `7d` | How long sent, given-up and replaced messages stay in the table |

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
| `max-crl-bytes`        | `16777216`          | A larger list is dropped as it downloads     |
| `connect-timeout`      | `10s`               | Reaching a distribution point or responder   |
| `read-timeout`         | `30s`               | Each wait for one to answer                  |
| `max-fetch-time`       | `60s`               | The whole of one download, redirects included |
| `allowed-hosts`        | empty (anywhere)    | The only hosts lists and responders are fetched from, when set: names, `*.domain`, addresses or ranges. Link-local and metadata addresses never; loopback only when named |

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
| `admin-identifiers`               | `[]`    | uids or entry DNs granted `ROLE_ADMIN`; an address is ignored, with a warning |
| `x509.enabled`                    | `true`  | Accept client certificates                    |
| `x509.require-known-certificate`  | `true`  | Match by fingerprint against the cache        |
| `ldap.enabled`                    | `true`  | Offer the password fallback                   |
| `ldap.user-search-base`           | `""`    | Where to look the username up                 |
| `ldap.user-search-filter`         | `(uid={0})` | How to look the username up               |
| `ldap.user-dn-patterns`           | `[]`    | Bind straight to a DN shape instead           |

A demo, under `cert-alert.demo` — see [Demo mode](#demo-mode), and never against a real
directory:

| Property             | Default        | Purpose                                         |
|----------------------|----------------|-------------------------------------------------|
| `enabled`            | `false`        | Sign in as any listed account, every write refused   |
| `password`           | `password`     | What every demo account carries, printed on the sign-in page |
| `generate-directory` | `true`         | Bring a directory of its own, generated at startup   |
| `people` / `servers` | `24` / `16`    | How much to generate                                 |
| `seed`               | `20260101`     | Fixes the choices; the dates always move with startup |

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

## Upgrade notes

Read these before upgrading. They apply to the compose bundle, the Helm chart and a plain
`java -jar` alike.

### Jobs started by hand answer 202, and run one at a time across replicas

`POST /api/v1/sync` and the other job endpoints no longer run the job inside the request: they
answer **202 Accepted** with a run (`statusUrl`), and `GET /api/v1/jobs/{id}` gives the result
once it has finished (see [Starting a job by hand](#starting-a-job-by-hand)). The pages do
this themselves. A script that read the counts from the `POST` response must now poll
`statusUrl` and read `result`. `POST /api/v1/notifications/digest?dryRun=true` still answers
at once.

A job started while the same job is running - scheduled or by hand - is now `SKIPPED` instead
of running alongside it.

### New tables, migrated on start

Flyway adds `shedlock` (V21), `job_run` (V22), `spring_session` and
`spring_session_attributes` (V23) and `saml_authn_request` (V24) on the first start. Nothing
to do; the database user already owns the schema.

### Sessions are kept in the database

With the postgres profile (the chart and the release bundle), sessions are now stored in the
database and survive a restart. The session cookie is now called `SESSION` instead of
`JSESSIONID`, so everybody signs in once more after the upgrade. Set
`CERT_ALERT_SESSION_STORE=memory` to keep sessions in the container as before.

### Helm: strategy and replicas

`strategy` now defaults to empty, which chooses `Recreate` for one replica (as before) and
`RollingUpdate` above one; a values file that sets `strategy.type` keeps it. `replicaCount`
may now be above 1, with `persistence.license.readWriteMany: true` and a ReadWriteMany license
volume. The access mode of an existing claim cannot be changed: create a ReadWriteMany claim,
copy `/var/lib/ens/certalert` into it with the pod stopped, and name it in
`persistence.license.existingClaim`. See [High availability](#high-availability).

### Single sign-on and the auditor role are new, and off

Nothing changes until `cert-alert.sso.*`, `auditor-identifiers` or `security.groups` are set.
Certificate and password sign-in work as before.

### Administrators are matched by uid or DN only

`cert-alert.security.admin-identifiers` (`CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS`,
`security.adminIdentifiers` in Helm) is matched against a person's uid and the DN of their
entry, and nothing else. An e-mail address in the list is **ignored**: a directory may let
people change their own `mail`, so matching on it let anybody make themselves an
administrator.

- **What happens on upgrade.** The application starts as before. Every uid and DN in the
  list still counts. For each address, startup logs a warning that names the uid and DN the
  directory cache holds for it. If the list holds only addresses, a second warning says that
  nobody is an administrator. A Helm install also warns in its NOTES.
- **What to do.** Replace each address with the uid the warning names, e.g.
  `CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS=alice` instead of `alice@example.gov`. List a DN
  through indexed variables (`CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS_0=uid=alice,ou=people,dc=example,dc=gov`)
  or a block-style YAML list. Where uids are themselves addresses, list the DN.
- The compose file's default administrator is now `alice`, not `alice@intelink.ic.gov`.

### Client certificates are checked for revocation at sign-in by default

With `ENS_X509_REVOCATION` unset, a client certificate is now checked live, over OCSP and
then its CRL. Before, nothing was checked live.

- **Soft-fail by default.** A certificate found revoked is refused and audited
  (`auth.login`, reason `certificate revoked`). One that no responder or CRL answers for
  signs in, with a warning in the log. Startup says the check is soft-fail.
- **What to do.**
  - For the STIG's fail-closed behaviour, set `ENS_X509_REVOCATION_FAIL=closed`.
  - On an air-gapped network, set `ENS_X509_CRL_DIR`, or an `ENS_X509_OCSP_RESPONDER_URL`
    relay, so the check has an answer and sign-in does not wait on fetches that cannot
    succeed.
  - To keep the old behaviour, set `ENS_X509_REVOCATION=off`.
- **Already set?** If you set `ENS_X509_REVOCATION` yourself and left
  `ENS_X509_REVOCATION_FAIL` unset, nothing changes: that combination stays fail-closed.
- **Helm.** `x509.revocation` and `x509.revocationFail` now default to `""`, the application
  default. A values file that sets `revocation: "off"` keeps the check off.
- **Outbound access.** Sign-in now fetches from the OCSP and CRL addresses that client
  certificates name. These are held to the same `cert-alert.revocation.allowed-hosts` and
  address rules as the nightly revocation job.

### The expiry round-up has a look-back and pages its reads

- **Look-back for expired certificates.** Only certificates that expired in the last 90
  days are reported as expired (`cert-alert.notifications.digest.expired-look-back`,
  `CERT_ALERT_NOTIFICATIONS_DIGEST_EXPIRED_LOOK_BACK`). Older ones, which were reported in
  every round-up until now, no longer are. Set `0` to report them all again.
- **Renewed certificates are left out by the query.** A certificate already renewed under
  the same subject is skipped in the query and no longer uses up the limit. The log line's
  "already replaced" count includes these.
- **A higher limit, read in pages.** Each read is paged
  (`cert-alert.notifications.digest.page-size`, 1,000), and `scan-limit` rises from 10,000
  to 50,000 per kind. A run that reaches it still logs a warning and counts in
  `ens_certalert_digest_truncated_total`.

## Releases

Customers install from one of two things, both built here from the same tested image: the
Helm chart, or a self-contained bundle rather than an image name - the production compose
file, an install and an upgrade script, every setting commented in `env.template`, and the
images themselves, so an air-gapped site needs nothing else.

**Cutting a release.** Push a tag:

```bash
git tag v1.2.3 && git push origin v1.2.3
```

The `image` job builds and smoke-tests the image as on every push, publishes it (including
`ghcr.io/winllc/cert-alert:1.2.3`), and then, from that same image, builds the bundle with
`scripts/release-bundle.sh`, which also packages the Helm chart at the same version; the
chart is then pushed to `oci://ghcr.io/winllc/charts`. A tag with a suffix (`v1.2.3-rc.1`) is a pre-release: built
and uploaded the same way, but `latest.txt` does not move. Every pull request builds the
bundle and the chart too, as `0.0.0-pr<number>`, kept for three days as a workflow
artifact and never uploaded or pushed - so a change that breaks the bundle shows up before the tag does.

**What CI produces**, kept as the workflow artifact `release-cert-alert-<version>` (30 days)
and uploaded to `s3://<bucket>/cert-alert/<version>/`:

| File | |
|---|---|
| `cert-alert-<version>.tar.gz` | the bundle: `README.md`, `VERSION`, `install.sh`, `upgrade.sh`, `docker-compose.yml` (application and PostgreSQL, pinned to this version's images; directory and SMTP are the customer's, set in `.env`), `env.template`, `license/README.txt`, and `images/cert-alert-images-<version>.tar` (`docker save` of cert-alert and postgres) |
| `cert-alert-<version>.tgz` | the Helm chart at this version (also pushed to `oci://ghcr.io/winllc/charts/cert-alert`) |
| `sbom-cert-alert.spdx.json`, `sbom-postgres.spdx.json` | SPDX SBOM per image (syft) |
| `SHA256SUMS` | checksums of all of the above |
| `SHA256SUMS.asc` | detached GPG signature of `SHA256SUMS`, when a signing key is configured |

plus `s3://<bucket>/cert-alert/latest.txt` holding the newest final version. The job summary
lists the files and sizes, and says whether it was signed and where it went - or why not.

**Configuring it** (Settings → Secrets and variables → Actions). With none of these set the
workflow still builds the bundle and keeps it as an artifact; signing and upload are each
skipped and the summary says so.

| Name | Kind | |
|---|---|---|
| `RELEASE_GPG_PRIVATE_KEY` | secret | ASCII-armoured private key that signs `SHA256SUMS` |
| `RELEASE_GPG_PASSPHRASE` | secret | its passphrase, if it has one |
| `RELEASE_S3_BUCKET` | variable | the private bucket releases go to |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | secrets | credentials that may write to it |
| `RELEASE_S3_ENDPOINT` | variable | for Cloudflare R2 or MinIO, e.g. `https://<account>.r2.cloudflarestorage.com`; empty for AWS S3 |
| `RELEASE_S3_REGION` | variable | default `us-east-1` (`auto` for R2) |

**Building one locally** (release engineering, not an install route) - any image tagged
with the product's name will do:

```bash
docker build -t ghcr.io/winllc/cert-alert:1.2.3 .
scripts/release-bundle.sh 1.2.3 ghcr.io/winllc/cert-alert:1.2.3   # into dist/release-1.2.3/
```

SBOMs are generated when `syft` is on the PATH, and the chart is packaged when `helm` is;
`POSTGRES_IMAGE` overrides the bundled
PostgreSQL (`postgres:17.11-alpine`).

**How a customer checks a download**, in the directory holding the files:

```bash
sha256sum -c SHA256SUMS                      # every file is the one that was published
gpg --import ens-release-key.asc             # once: the ENS Solutions release public key
gpg --verify SHA256SUMS.asc SHA256SUMS       # and SHA256SUMS is the one ENS signed
```

then `tar xzf cert-alert-<version>.tar.gz` and follow the `README.md` inside: `./install.sh`
for a first install, `./upgrade.sh --from ../cert-alert-<old version>` to move to a new one.
Or install `cert-alert-<version>.tgz` with Helm (see `deploy/helm/cert-alert/README.md`).

**Signed images and charts.** On a release tag the image and the Helm chart are signed with
cosign, keylessly - the signature is a certificate naming this repository's workflow, issued
through GitHub's OIDC, with no key to keep - always by digest, and the image's SPDX SBOM is
attached to it as an attestation. The job summary prints the digests. To check them:

```bash
cosign verify ghcr.io/winllc/cert-alert@<digest> \
  --certificate-identity-regexp 'https://github.com/winllc/cert-alert/.github/workflows/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com

cosign verify-attestation --type spdxjson ghcr.io/winllc/cert-alert@<digest> \
  --certificate-identity-regexp 'https://github.com/winllc/cert-alert/.github/workflows/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com

cosign verify ghcr.io/winllc/charts/cert-alert@<digest> \
  --certificate-identity-regexp 'https://github.com/winllc/cert-alert/.github/workflows/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

`docker buildx imagetools inspect ghcr.io/winllc/cert-alert:<version>` gives the image's
digest; `helm pull` prints the chart's.

**The Helm chart** is checked on every push and pull request by the `helm` job:
`helm lint --strict` and `helm template` with each `deploy/helm/cert-alert/ci/*.yaml`, the
output validated by kubeconform against Kubernetes 1.29.

## Project layout

```
Dockerfile               three-stage image build
docker-compose.yml       the application, a PostgreSQL and a stand-in directory
docker-compose.ports.yml opt-in overlay that publishes them on the host
docker/389ds/            389 Directory Server carrying the IC FSD schema and a changelog
deploy/release/          what goes in a customer release bundle: production compose,
                         env.template, install.sh, upgrade.sh and the customer README
deploy/helm/cert-alert/  the Helm chart, its README and the example values in ci/
scripts/                 the dummy directory generator, and release-bundle.sh
src/main/java/com/enssolutions/license/   the ENS portal's license verifier, copied unchanged
src/main/java/com/winllc/certalert/
├── alert/       notifier SPI, dispatcher, log and email channels
├── config/      expiry thresholds, clock, DataTables repository factory
├── domain/      DirectoryUser, DirectoryServer, CachedCertificate, ServerContact, AuditEvent
├── ldap/        attribute mapping, paged streaming directory client
├── license/     ENS license wiring: keys, feature catalog, request gate, the Licensing facade
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
├── dev-directory.ldif     sample directory for the dev profile
└── demo-accounts.ldif     the accounts the demo's sign-in page offers
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

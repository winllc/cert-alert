# cert-alert @VERSION@

cert-alert reads the certificates your LDAP directory publishes and tells the right people
before they expire. This bundle is everything needed to run it with Docker Compose or with
Podman, including on a host with no internet access.

Install guide: <https://winllc.github.io/ens-apps-documentation/cert-alert/install/>

Installing on Kubernetes or OpenShift instead? Use the Helm chart,
`oci://ghcr.io/winllc/charts/cert-alert`, at this same version (also in the release download
as `cert-alert-@VERSION@.tgz`).

## What is here

| | |
|---|---|
| `install.sh` | first install (safe to re-run) |
| `upgrade.sh` | move an existing install to this release |
| `docker-compose.yml` | cert-alert and its PostgreSQL, pinned to this release's images |
| `docker-compose.no-ports.yml` | publishes nothing on the host, for a proxy on the compose network (see below) |
| `container-engine.sh` | picks Docker or Podman for the scripts |
| `env.template` | every setting, commented; copied to `.env` on install |
| `license/` | put your `license.lic` here for `install.sh` to install (or upload it on the license screen) |
| `images/` | the container images (loaded by the scripts) |
| `VERSION` | this release |

## Before you start

- Either **Docker** Engine 24+ with the Compose plugin v2 (`docker compose version`; a
  standalone `docker-compose` v2 works too, v1 does not), or **Podman** 4.0+ with
  **podman-compose 1.3.0+** (`pip install podman-compose`, or your distribution's package if
  it is that new; `podman compose` is used when podman-compose is not on `PATH`). Rootful or
  rootless - see [Podman](#podman) below. About 3 GB of disk.
- A service account on your directory that can read people, servers and their
  certificates. If the directory's certificate comes from a private CA, see `JAVA_OPTS`
  in `env.template`.
- Optional: an SMTP server for email, and a keystore if users will sign in with client
  certificates (TLS).

Verify the download first, in the directory holding the tarball:

```bash
sha256sum -c SHA256SUMS --ignore-missing
gpg --verify SHA256SUMS.asc SHA256SUMS      # with the ENS Solutions release key imported
```

## Install

```bash
tar xzf cert-alert-@VERSION@.tar.gz
cd cert-alert-@VERSION@
./install.sh          # loads the images, creates .env, and tells you what to fill in
vi .env               # your directory, administrators, SMTP, TLS ...
cp /path/to/license.lic license/license.lic     # optional - or upload it once it is running
./install.sh          # validates .env, starts it, and installs license/license.lic
```

The scripts use Docker when its daemon answers, and Podman otherwise; they print which, and
the first run records it as `CONTAINER_ENGINE` in `.env` so later runs and upgrades use the
same one. `./install.sh --engine podman` (or `CONTAINER_ENGINE=podman ./install.sh`) chooses.

The UI is then on port 8080 of every interface of this host (`CERT_ALERT_PORT` and
`CERT_ALERT_BIND_ADDRESS` in `.env`). Sign in as one of `CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS`
with a directory password - or a client certificate with TLS on - and press
**Sync directory**.

## Listening, and TLS

A new install publishes the UI on **every interface** (`CERT_ALERT_BIND_ADDRESS=0.0.0.0` in
`.env`), port 8080 (`CERT_ALERT_PORT`). With TLS off that is plain HTTP, where directory
passwords and session cookies cross the network in clear text, and `install.sh` warns so
every time it runs. Choose one of:

- **Turn TLS on in cert-alert** (`CERT_ALERT_TLS_ENABLED=true`, keystore and truststore in
  `tls/`), keeping `0.0.0.0` (or set the interface's address). Client certificate (CAC/PIV)
  sign-in needs this, or the proxy's header mode.
- **Put a reverse proxy on this host in front** (nginx, Apache) that terminates TLS and
  forwards to `http://127.0.0.1:8080`. Restrict cert-alert to this host with
  `CERT_ALERT_BIND_ADDRESS=127.0.0.1`, and set `SERVER_FORWARD_HEADERS_STRATEGY=framework`
  and `ENS_COOKIE_SECURE=true`.
- **Run the proxy as a container on the compose network** (`cert-alert_default`), forwarding
  to `http://cert-alert:8080`, and publish nothing on the host:
  `cp docker-compose.no-ports.yml docker-compose.override.yml`, then `./install.sh`. Both
  compose commands read that override file on their own, and upgrades carry it over. It
  needs Docker Compose 2.24+ or podman-compose 1.4.0+ (for `!reset`).

To restrict who can reach it otherwise, set `CERT_ALERT_BIND_ADDRESS` to one interface's
address, or firewall the port. Run `./install.sh` (or `docker compose up -d`) after changing
`.env`. With TLS on,
cert-alert offers TLS 1.2 and 1.3 only, with FIPS-approved suites only (AES-GCM, and ECDHE on
TLS 1.2; `CERT_ALERT_TLS_CIPHERS` replaces the list). Idle sessions end after 15 minutes
(`ENS_SESSION_IDLE_TIMEOUT`).

**Upgrading keeps the address and port in `.env`.** An install that listens on `127.0.0.1`
stays there - the upgrade prints a note, and never opens it to the network; set
`CERT_ALERT_BIND_ADDRESS=0.0.0.0` yourself when you want that. If `.env` does not set
`CERT_ALERT_BIND_ADDRESS` and was written for the release that listened on `127.0.0.1` by
default, the upgrade adds `CERT_ALERT_BIND_ADDRESS=127.0.0.1` to it under a comment saying so;
an `.env` from a release before that keeps `0.0.0.0`, as it was. `install.sh` no longer
refuses plain HTTP on another address; it warns, and `CERT_ALERT_ALLOW_PLAIN_HTTP` is no
longer read (delete it when you like). Two defaults do change: idle sessions end after 15
minutes rather than 30 (`ENS_SESSION_IDLE_TIMEOUT=30m` restores it), and with TLS on a client
that can do only ChaCha20, CBC or RSA-key-exchange suites can no longer connect.

An existing Docker install needs nothing new: with no `CONTAINER_ENGINE` in `.env` the
scripts still pick Docker when its daemon answers. On a host that also has Podman, add
`CONTAINER_ENGINE=docker` to `.env` to make sure. Moving an install from Docker to Podman is
a new install plus a database restore (the volumes are the engine's own), not an upgrade.

## Podman

The same files work with Podman 4.0+ and podman-compose 1.3.0+, rootful (`sudo`) or rootless
(your own user). `install.sh` loads the images into Podman's store, and the compose file
names them fully (`docker.io/library/postgres`, `ghcr.io/winllc/cert-alert`), as Podman has
no default registry.

- **Health checks run from systemd timers.** The database must report healthy before
  cert-alert starts, so the host needs systemd: as root, nothing to do; rootless, your user
  needs a systemd session - log in normally (not `su`), and see linger below.
- **Rootless: ports from 1024 up.** An unprivileged user cannot publish 80 or 443 unless
  `net.ipv4.ip_unprivileged_port_start` is lowered; keep `CERT_ALERT_PORT` at 8080 (or any
  port from 1024), and put a proxy on 443 if you want it. `install.sh` checks this.
- **Rootless: keep it running after you log out:** `sudo loginctl enable-linger $USER`.
  Otherwise systemd stops your containers when your last session ends.
- **Start at boot.** Podman has no daemon to bring containers back after a reboot. Run
  `podman-compose up -d` in this directory from a systemd unit (a user unit, with linger,
  when rootless), or generate units from the running containers with
  `podman generate systemd --new --files --name <container>` (or Quadlet `.container` files
  on Podman 4.4+).
- **SELinux** (RHEL, Fedora): the `tls/` and `email-templates/` mounts carry `:Z`, so they
  are relabelled for the container. The database, license and audit data are named volumes,
  which need no labels.
- **Rootless: file ownership.** The named volumes are owned correctly by Podman. Files you
  put in `tls/` and `email-templates/` appear inside the container as owned by root, and
  cert-alert runs as uid 185, so make them readable by it: `chmod -R a+rX tls email-templates`
  (with keystore passwords in `.env`), or hand them to uid 185 inside your user namespace with
  `podman unshare chown -R 185:0 tls email-templates`.
- **Day to day** use `podman-compose` where this README says `docker compose`, and `podman`
  where it says `docker`.

## License

cert-alert needs an ENS license, from the ENS licensing portal: an evaluation, or the
license issued against your order (product `certalert`). Until one is installed, only
signing in and the license screen work - administrators are sent there.

- **Install it** by putting it at `license/license.lic` before running `./install.sh`, which
  copies it into the application's license volume once it has started; or upload it on the
  license screen, **Administration → License** (`/admin/license`). It takes effect at once.
  `install.sh` never replaces a different license already installed (it may be a newer one
  uploaded on the screen): upload the new file on the screen instead.
- **The license volume** (`ens-license`, at `/var/lib/ens/certalert` in the container) holds
  the license, this installation's id and the license revisions seen. Upgrades keep it.
  `docker compose down -v` deletes it, and the installation gets a new id.
- **Binding.** The license screen shows this installation's id (e.g. `CA-7F3K-29QD`). Enter
  it on the ENS portal to bind a license to this installation.
- **Expiry.** A subscription keeps full use through its grace period, with a reminder to
  administrators; after that cert-alert is read-only - everything readable, nothing
  changeable, and the directory sweeps, scheduled jobs and email paused - until a renewed
  license is installed.

## Upgrade

Unpack the new release next to the old one and point it at the old directory:

```bash
tar xzf cert-alert-<new>.tar.gz
cd cert-alert-<new>
./upgrade.sh --from ../cert-alert-<old>
```

It copies `.env`, `license/`, `tls/` and `email-templates/` across (never overwriting),
keeps the installed license and installation id (they are in the `ens-license` volume),
lists any settings new in this release, backs up the database to `backups/`, loads the
new images and restarts on them. The database is kept and migrated on start; migrations
are not undone, so going back to an older release means restoring that backup into it.

It runs on the engine `.env` names (`CONTAINER_ENGINE`), or, for an `.env` from before this
release, Docker if its daemon answers - so an existing Docker install upgrades with no
manual steps. It keeps the listen address and port in `.env`: see
[Listening, and TLS](#listening-and-tls) - an install on `127.0.0.1` stays there.

Read the upgrade notes in the main README first. Two of them change what `.env` needs:

- `CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS` now matches uids (or entry DNs) only. An e-mail
  address there is ignored, and a warning at startup names the uid to use instead.
- With `ENS_X509_REVOCATION` unset, client certificates are now checked for revocation at
  sign-in, soft-fail. Set `ENS_X509_REVOCATION_FAIL=closed` for the STIG, `ENS_X509_CRL_DIR`
  on an air-gapped network, or `ENS_X509_REVOCATION=off` for the old behaviour.

## Day to day

```bash
docker compose ps                      # status and health (or podman-compose ps)
docker compose logs -f cert-alert      # application log
docker compose up -d                   # after editing .env (restart alone does not re-read it)
docker compose down                    # stop (data kept)
docker compose exec -T postgres pg_dump -U certalert certalert | gzip > backup.sql.gz
```

`docker compose down -v` also deletes the database volume, and the license volume with the
installed license and this installation's id. With Podman, the same commands with
`podman-compose`.

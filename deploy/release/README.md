# cert-alert @VERSION@

cert-alert reads the certificates your LDAP directory publishes and tells the right people
before they expire. This bundle is everything needed to run it with Docker Compose,
including on a host with no internet access.

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
| `env.template` | every setting, commented; copied to `.env` on install |
| `license/` | put your `license.lic` here for `install.sh` to install (or upload it on the license screen) |
| `images/` | the container images (`docker load`ed by the scripts) |
| `VERSION` | this release |

## Before you start

- Docker Engine 24+ with the Compose plugin (`docker compose version`), and about 3 GB of disk.
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

The UI is then on port 8080 of this host (`CERT_ALERT_PORT` moves it). Sign in as one of
`CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS` with a directory password - or a client
certificate with TLS on - and press **Sync directory**.

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

## Day to day

```bash
docker compose ps                      # status and health
docker compose logs -f cert-alert      # application log
docker compose up -d                   # after editing .env (restart alone does not re-read it)
docker compose down                    # stop (data kept)
docker compose exec -T postgres pg_dump -U certalert certalert | gzip > backup.sql.gz
```

`docker compose down -v` also deletes the database volume, and the license volume with the
installed license and this installation's id.

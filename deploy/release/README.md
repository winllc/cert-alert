# cert-alert @VERSION@

cert-alert reads the certificates your LDAP directory publishes and tells the right people
before they expire. This bundle is everything needed to run it with Docker Compose,
including on a host with no internet access.

Full documentation: <https://github.com/winllc/cert-alert#readme>

## What is here

| | |
|---|---|
| `install.sh` | first install (safe to re-run) |
| `upgrade.sh` | move an existing install to this release |
| `docker-compose.yml` | cert-alert and its PostgreSQL, pinned to this release's images |
| `env.template` | every setting, commented; copied to `.env` on install |
| `license/` | where your `license.lic` goes |
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
cp /path/to/license.lic license/ && chmod 644 license/license.lic
./install.sh          # validates .env and starts it
```

The UI is then on port 8080 of this host (`CERT_ALERT_PORT` moves it). Sign in as one of
`CERT_ALERT_SECURITY_ADMIN_IDENTIFIERS` with a directory password - or a client
certificate with TLS on - and press **Sync directory**.

Without a license cert-alert runs its core features only. A license copied into
`license/` later is picked up within about 30 seconds, with no restart.

## Upgrade

Unpack the new release next to the old one and point it at the old directory:

```bash
tar xzf cert-alert-<new>.tar.gz
cd cert-alert-<new>
./upgrade.sh --from ../cert-alert-<old>
```

It copies `.env`, `license/`, `tls/` and `email-templates/` across (never overwriting),
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

`docker compose down -v` also deletes the database volume.

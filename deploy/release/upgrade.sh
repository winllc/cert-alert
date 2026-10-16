#!/usr/bin/env bash
# cert-alert - upgrade to this release.
#
#   ./upgrade.sh --from ../cert-alert-<old version>   unpacked next to the previous release
#   ./upgrade.sh                                      .env, license/ and tls/ already copied here
#
# Copies .env, license/, tls/ and email-templates/ from the previous release (never
# overwriting what is here), backs up the database (--no-backup to skip), loads the new
# images and restarts on them. The database and license volumes are kept (the installed
# license and this installation's id stay); the schema is migrated on start.
set -euo pipefail
exec "$(dirname "$0")/install.sh" --upgrade "$@"

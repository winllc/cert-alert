cert-alert needs an ENS Solutions license, issued by the ENS licensing portal: download
an evaluation, or the license issued against your order. Its product id is "certalert".

There are two ways to install it:

  1. Put the file here, named exactly

         license.lic

     and run ./install.sh (or ./upgrade.sh). Before starting cert-alert, the script puts
     it into .env as ENS_LICENSE, and cert-alert installs it as it starts: when no usable
     license is installed yet, or when it is a newer revision of the installed one. A
     different license already installed is kept - upload that one on the license screen
     instead.

  2. Upload it on the license screen: sign in as an administrator and open
     Administration -> License (/admin/license). An unlicensed cert-alert sends
     administrators there.

Until a license is installed, only signing in and the license screen work. An upload
takes effect at once, with no restart.

The license screen also shows this installation's id. Enter it on the ENS portal to
bind a license to this installation; a bound license works here and nowhere else. The
id is kept in the database, with the license, so keep the database: upgrades do.
Deleting it (docker compose down -v) gives the installation a new id.

To check what is installed:

    docker compose logs cert-alert | grep -i license

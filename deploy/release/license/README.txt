Put your ENS Solutions license file here, named exactly:

    license.lic

It is mounted read-only into the application at /etc/ens/license/license.lic. The
application runs as uid 185, so the file must be readable by it:

    chmod 644 license.lic

Without a license cert-alert runs its core features only. A license added or renewed
while it runs is picked up within about 30 seconds - no restart needed. To check what
was found:

    docker compose logs cert-alert | grep 'License:'

Licenses are issued by ENS Solutions for the product id "cert-alert".

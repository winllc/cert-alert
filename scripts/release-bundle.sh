#!/usr/bin/env bash
# Builds the customer release bundle for cert-alert.
#
#   scripts/release-bundle.sh <version> <image-ref>...
#   scripts/release-bundle.sh 1.2.3 ghcr.io/winllc/cert-alert:1.2.3
#
# The image refs are the product's own images, already present locally (CI passes the one it
# just smoke-tested, retagged to its published name). Third-party images the compose file
# needs - PostgreSQL - are added here and pulled if absent.
#
# Writes to $OUT_DIR (default dist/release-<version>):
#
#   cert-alert-<version>.tar.gz     unpacks to cert-alert-<version>/: README.md, VERSION,
#                                   install.sh, upgrade.sh, docker-compose.yml, env.template,
#                                   license/README.txt, images/cert-alert-images-<version>.tar
#   sbom-<image>.spdx.json          SPDX SBOM per image, when syft is installed ($SYFT names
#                                   it; REQUIRE_SBOM=true makes its absence an error, as in CI)
#   cert-alert-<version>.tgz        the Helm chart (deploy/helm/cert-alert) packaged at this
#                                   version, when helm is installed ($HELM names it;
#                                   REQUIRE_CHART=true makes its absence an error, as in CI)
#   SHA256SUMS                      over all of the above
#
# Signing SHA256SUMS and uploading are CI's job (.github/workflows/ci.yml).
set -euo pipefail

PRODUCT=cert-alert
POSTGRES_IMAGE=${POSTGRES_IMAGE:-postgres:17.11-alpine}
REQUIRE_SBOM=${REQUIRE_SBOM:-false}
SYFT=${SYFT:-syft}
REQUIRE_CHART=${REQUIRE_CHART:-false}
HELM=${HELM:-helm}

usage() { awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 2; }
die() { printf 'release-bundle: %s\n' "$*" >&2; exit 1; }

[ $# -ge 2 ] || usage
VERSION=$1
shift
APP_IMAGES=("$@")

[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] \
    || die "version '$VERSION' is not X.Y.Z or X.Y.Z-suffix (no leading v)"

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
TEMPLATE_DIR="$REPO_ROOT/deploy/release"
OUT_DIR=${OUT_DIR:-"$REPO_ROOT/dist/release-$VERSION"}
NAME="$PRODUCT-$VERSION"

command -v docker > /dev/null || die "docker is required"
docker compose version > /dev/null 2>&1 || die "the docker compose plugin is required"

# The product image: the one ref whose repository ends in /cert-alert (or is cert-alert).
APP_IMAGE=""
for ref in "${APP_IMAGES[@]}"; do
    repo=${ref%:*}
    if [ "${repo##*/}" = "$PRODUCT" ]; then
        [ -z "$APP_IMAGE" ] || die "more than one $PRODUCT image given: $APP_IMAGE, $ref"
        APP_IMAGE=$ref
    fi
done
[ -n "$APP_IMAGE" ] || die "none of the image refs is a $PRODUCT image: ${APP_IMAGES[*]}"
[ "${APP_IMAGE##*:}" = "$VERSION" ] \
    || echo "release-bundle: warning: $APP_IMAGE is not tagged with the version $VERSION" >&2

ALL_IMAGES=("${APP_IMAGES[@]}" "$POSTGRES_IMAGE")

for ref in "${APP_IMAGES[@]}"; do
    docker image inspect "$ref" > /dev/null 2>&1 \
        || die "image $ref is not present locally (build or retag it first)"
done
if ! docker image inspect "$POSTGRES_IMAGE" > /dev/null 2>&1; then
    echo "Pulling $POSTGRES_IMAGE"
    docker pull "$POSTGRES_IMAGE"
fi

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
OUT_DIR=$(cd "$OUT_DIR" && pwd)
WORK=$(mktemp -d "${TMPDIR:-/tmp}/release-bundle.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
STAGE="$WORK/$NAME"
mkdir -p "$STAGE/license" "$STAGE/images"

echo "Staging $NAME"
printf '%s\n' "$VERSION" > "$STAGE/VERSION"
sed "s|@VERSION@|$VERSION|g" "$TEMPLATE_DIR/README.md" > "$STAGE/README.md"
sed -e "s|@CERT_ALERT_IMAGE@|$APP_IMAGE|g" -e "s|@POSTGRES_IMAGE@|$POSTGRES_IMAGE|g" \
    "$TEMPLATE_DIR/docker-compose.yml" > "$STAGE/docker-compose.yml"
cp "$TEMPLATE_DIR/env.template" "$STAGE/env.template"
cp "$TEMPLATE_DIR/license/README.txt" "$STAGE/license/README.txt"
install -m 0755 "$TEMPLATE_DIR/install.sh" "$STAGE/install.sh"
install -m 0755 "$TEMPLATE_DIR/upgrade.sh" "$STAGE/upgrade.sh"
chmod 0644 "$STAGE/VERSION" "$STAGE/README.md" "$STAGE/docker-compose.yml" \
    "$STAGE/env.template" "$STAGE/license/README.txt"

if grep -q '@[A-Z_]*@' "$STAGE/docker-compose.yml" "$STAGE/README.md"; then
    die "unreplaced placeholder left in the staged files"
fi

# The compose file must reference exactly the images being shipped, and validate.
echo "Validating docker-compose.yml"
CHECK="$WORK/check"
mkdir -p "$CHECK"
cp "$STAGE/docker-compose.yml" "$CHECK/"
sed 's/^CERT_ALERT_DB_PASSWORD=$/CERT_ALERT_DB_PASSWORD=validate-only/' "$STAGE/env.template" > "$CHECK/.env"
(cd "$CHECK" && docker compose config --quiet) || die "docker-compose.yml does not validate"
compose_images=$(cd "$CHECK" && docker compose config --images | sort -u)
expected=$(printf '%s\n' "$APP_IMAGE" "$POSTGRES_IMAGE" | sort -u)
[ "$compose_images" = "$expected" ] \
    || die "compose images ($compose_images) differ from the bundled ones ($expected)"
if grep -q '^[[:space:]]*build:' "$STAGE/docker-compose.yml"; then
    die "the release compose file must not build anything"
fi
# The license, the installation id and the revisions seen live in the ens-license named
# volume, which upgrades keep (the compose project name is fixed) - never in a bind mount.
(cd "$CHECK" && docker compose config --volumes) | grep -qx ens-license \
    || die "docker-compose.yml does not declare the ens-license volume"
grep -q 'ens-license:/var/lib/ens/certalert$' "$STAGE/docker-compose.yml" \
    || die "docker-compose.yml does not mount ens-license at /var/lib/ens/certalert"
if grep -q '\./license' "$STAGE/docker-compose.yml"; then
    die "docker-compose.yml bind-mounts ./license; the license belongs in the ens-license volume"
fi

echo "Saving images: ${ALL_IMAGES[*]}"
printf '%s\n' "${ALL_IMAGES[@]}" > "$STAGE/images/IMAGES"
docker save -o "$STAGE/images/$PRODUCT-images-$VERSION.tar" "${ALL_IMAGES[@]}"
chmod 0644 "$STAGE/images/"*

# Nothing secret may ship: no license, no key, no .env.
leaks=$(find "$STAGE" \( -name '*.lic' -o -name '*.pem' -o -name '*.key' -o -name '*.p12' \
    -o -name '*.jks' -o -name '.env' -o -name '.env.*' \) -print)
[ -z "$leaks" ] || die "refusing to bundle: $leaks"

echo "Packing $NAME.tar.gz"
tar --owner=0 --group=0 --numeric-owner -C "$WORK" -czf "$OUT_DIR/$NAME.tar.gz" "$NAME"

if command -v "$SYFT" > /dev/null 2>&1; then
    for ref in "${ALL_IMAGES[@]}"; do
        short=${ref%%@*}; short=${short%:*}; short=${short##*/}
        echo "SBOM for $ref -> sbom-$short.spdx.json"
        "$SYFT" -q "docker:$ref" -o "spdx-json=$OUT_DIR/sbom-$short.spdx.json"
    done
elif [ "$REQUIRE_SBOM" = true ]; then
    die "syft is not installed and REQUIRE_SBOM=true"
else
    echo "release-bundle: warning: syft not installed, no SBOMs generated" >&2
fi

# The Helm chart, at the same version as the bundle and its images. The second way to
# install: CI also pushes it to oci://ghcr.io/winllc/charts on a release tag.
if command -v "$HELM" > /dev/null 2>&1; then
    echo "Packaging the Helm chart -> $PRODUCT-$VERSION.tgz"
    "$HELM" lint --strict "$REPO_ROOT/deploy/helm/$PRODUCT" \
        -f "$REPO_ROOT/deploy/helm/$PRODUCT/ci/default-values.yaml" > /dev/null \
        || die "the Helm chart does not lint"
    "$HELM" package "$REPO_ROOT/deploy/helm/$PRODUCT" --version "$VERSION" --app-version "$VERSION" \
        --destination "$OUT_DIR" > /dev/null
    [ -f "$OUT_DIR/$PRODUCT-$VERSION.tgz" ] || die "helm package did not write $PRODUCT-$VERSION.tgz"
elif [ "$REQUIRE_CHART" = true ]; then
    die "helm is not installed and REQUIRE_CHART=true"
else
    echo "release-bundle: warning: helm not installed, the chart was not packaged" >&2
fi

(
    cd "$OUT_DIR"
    shopt -s nullglob
    sha256sum -- "$NAME.tar.gz" "$PRODUCT"-*.tgz sbom-*.spdx.json > SHA256SUMS
)

echo
echo "Bundle in $OUT_DIR:"
(cd "$OUT_DIR" && ls -l)
echo
cat "$OUT_DIR/SHA256SUMS"

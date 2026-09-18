#!/usr/bin/env bash
#
# Writes an LDIF of dummy IC FSD people and servers, for loading into the stand-in
# directory that docker-compose.yml brings up.
#
# The certificates are real X.509, minted here by a throwaway CA, and spread across the
# states the application reports on: valid for years, expiring inside the warning window,
# expiring inside the critical one, already expired, and absent altogether. Points of
# contact are written both ways round - some as an address, some as a person's name -
# because the specification defines serverPOC as a name and real directories do both.
#
# Nothing here is a secret and nothing here should outlive a demo: every person carries
# the password "password".
#
#   ./scripts/generate-directory-data.sh --users 200 --servers 80 > directory.ldif
#
set -euo pipefail

USERS=100
SERVERS=40
BASE_DN="dc=example,dc=test"
OUT="-"
CERTIFICATES=true

usage() {
    cat >&2 <<'USAGE'
Usage: generate-directory-data.sh [options]

  --users N         people to generate (default 100)
  --servers N       servers to generate (default 40)
  --base DN         directory suffix (default dc=example,dc=test)
  --out FILE        write here instead of stdout
  --no-certificates skip certificate minting, which is what makes large runs slow
  -h, --help        this

Every person carries the password "password", so any of them can sign in. Servers are
non-person entries and carry none.
USAGE
}

while [ $# -gt 0 ]; do
    case "$1" in
        --users) USERS="$2"; shift 2 ;;
        --servers) SERVERS="$2"; shift 2 ;;
        --base) BASE_DN="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --no-certificates) CERTIFICATES=false; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage; exit 2 ;;
    esac
done

case "$USERS$SERVERS" in
    *[!0-9]*) echo "--users and --servers take whole numbers" >&2; exit 2 ;;
esac

if [ "$OUT" != "-" ]; then
    exec > "$OUT"
fi

log() { echo "$*" >&2; }

# ---------------------------------------------------------------------------------------
# Dates. GNU and BSD date disagree about relative times, and this script is as likely to
# be run on a laptop as inside the image.
# ---------------------------------------------------------------------------------------

if date -u -d "+1 days" +%Y >/dev/null 2>&1; then
    at_days() { date -u -d "$1 days" +%Y%m%d%H%M%SZ; }
else
    at_days() { date -u -v"$1"d +%Y%m%d%H%M%SZ; }
fi

# ---------------------------------------------------------------------------------------
# A throwaway CA, so certificates can be given exact validity windows. `openssl req -x509`
# cannot backdate one, and an expired certificate is half the point of this data.
# ---------------------------------------------------------------------------------------

CA_DIR=""
cleanup() {
    if [ -n "$CA_DIR" ]; then
        rm -rf "$CA_DIR"
    fi
    return 0
}
trap cleanup EXIT

setup_ca() {
    CA_DIR="$(mktemp -d)"
    export CA_DIR
    mkdir -p "$CA_DIR/newcerts"
    : > "$CA_DIR/index.txt"
    echo 1000 > "$CA_DIR/serial"
    cat > "$CA_DIR/openssl.cnf" <<'CONF'
[ ca ]
default_ca = dummy

[ dummy ]
new_certs_dir  = $ENV::CA_DIR/newcerts
database       = $ENV::CA_DIR/index.txt
serial         = $ENV::CA_DIR/serial
certificate    = $ENV::CA_DIR/ca.crt
private_key    = $ENV::CA_DIR/ca.key
default_md     = sha256
policy         = anything
email_in_dn    = no
unique_subject = no
copy_extensions = copy

[ anything ]
commonName = optional
CONF
    openssl req -new -newkey rsa:2048 -nodes -x509 -days 3650 \
        -subj "/CN=cert-alert dummy data CA" \
        -keyout "$CA_DIR/ca.key" -out "$CA_DIR/ca.crt" 2>/dev/null

    # A key per kind rather than one for everything, so a report on what the directory is
    # signing with has something to report. Generated once; signing is the cheap part.
    openssl genrsa -out "$CA_DIR/leaf-rsa2048.key" 2048 2>/dev/null
    openssl genrsa -out "$CA_DIR/leaf-rsa4096.key" 4096 2>/dev/null
    openssl genrsa -out "$CA_DIR/leaf-rsa1024.key" 1024 2>/dev/null
    openssl ecparam -name prime256v1 -genkey -noout -out "$CA_DIR/leaf-ec256.key" 2>/dev/null
}

# The mix. Most of a real directory is one thing; the interest is in the rest of it, and a
# metrics page with one row on it demonstrates nothing.
#              0             1             2             3             4              5
KEY_FILES=(leaf-rsa2048 leaf-rsa2048 leaf-rsa2048 leaf-rsa4096 leaf-rsa1024 leaf-ec256)
KEY_DIGESTS=(sha256      sha256       sha384       sha256       sha1         sha256)

# key_for <index> -> the key file to sign with
key_for() {
    printf '%s' "${KEY_FILES[$(( $1 % ${#KEY_FILES[@]} ))]}"
}

# digest_for <index> -> the message digest to sign with
digest_for() {
    printf '%s' "${KEY_DIGESTS[$(( $1 % ${#KEY_DIGESTS[@]} ))]}"
}

# certificate <common-name> <san> <not-before-days> <not-after-days> <key> <digest>
#   -> base64 DER
certificate() {
    local cn="$1" san="$2" from="$3" to="$4" key="$5" digest="$6"
    openssl req -new -key "$CA_DIR/$key.key" -subj "/CN=$cn" -addext "subjectAltName=$san" \
        -out "$CA_DIR/leaf.csr" 2>/dev/null
    openssl ca -batch -config "$CA_DIR/openssl.cnf" -in "$CA_DIR/leaf.csr" \
        -out "$CA_DIR/leaf.crt" -notext -md "$digest" \
        -startdate "$(at_days "$from")" -enddate "$(at_days "$to")" 2>/dev/null
    # A PEM body already is the base64 DER that LDIF wants, so it is read here rather than
    # piped through `openssl x509 | base64`. Three fewer processes per certificate, which
    # is most of the cost of minting one.
    local line body=""
    while IFS= read -r line; do
        # Git for Windows' openssl writes CRLF; a stray CR would split the LDIF value.
        line="${line%$'\r'}"
        case "$line" in
            -----*) continue ;;
        esac
        body="$body$line"
    done < "$CA_DIR/leaf.crt"
    printf '%s' "$body"
}

# The spread of expiry states, one slot per entry by index. The seventh gets no
# certificate at all.
#                  0: years out   1: warning    2: critical   3: expired    4,5: years out
NOT_BEFORE=(-30    -335   -360   -400   -60    -14)
NOT_AFTER=(+1825   +20    +5     -10    +3650  +900)

# certificate_for <index> <common-name> <san> -> base64 DER, or nothing
certificate_for() {
    local index="$1" cn="$2" san="$3" slot
    [ "$CERTIFICATES" = true ] || return 0
    slot=$((index % 7))
    [ "$slot" -eq 6 ] && return 0
    certificate "$cn" "$san" "${NOT_BEFORE[$slot]}" "${NOT_AFTER[$slot]}" \
        "$(key_for "$index")" "$(digest_for "$index")"
}

# ---------------------------------------------------------------------------------------
# Names. Cycled rather than random, so the same arguments always produce the same
# directory and a diff of two runs shows only what the dates did.
# ---------------------------------------------------------------------------------------

GIVEN_NAMES=(Alice Bob Carol Dana Evan Farah Grace Hugo Iris Jamal Kara Liam Mona Nadia
             Omar Petra Quinn Rosa Sami Tara Umar Vera Wes Xenia Yusuf Zoe)
# Archer, Wilson, Chase and Day are left out on purpose: they belong to the four people
# below that the README and the demo point at, and a generated namesake would make the
# point-of-contact join ambiguous for exactly the accounts somebody is looking at.
SURNAMES=(Blake Ellis Frost Grant Hale Ito Jensen Khan Lopez Marsh Novak
          Ortiz Park Quinlan Reyes Shaw Tran Usman Vance Walsh Xu Young Zamora)
TITLES=("Systems Engineer" "Database Administrator" "Security Officer" "Analyst"
        "Program Manager" "Network Engineer" "Data Scientist" "Watch Officer")
EMPLOYEE_TYPES=(Civilian Military Contractor)
RANKS=("" "Major" "")
ORGANIZATIONS=("Example Agency" "Example Bureau" "Example Office" "Example Directorate")
ROLES=(web db app cache mail dns proxy log ci vault)
LIFECYCLE=(Production Production Production Development Test Staging)
ATO_STATUS=(Authorized Authorized Authorized "In Process")

lower() { printf '%s' "$1" | tr '[:upper:]' '[:lower:]'; }

# Lowercased once here rather than per entry. Spawning `tr` a quarter of a million times
# is most of the runtime of a large directory, and `${name,,}` would cost bash 3.2, which
# is still what a Mac has.
GIVEN_LOWER=()
SURNAME_LOWER=()
for one in "${GIVEN_NAMES[@]}"; do GIVEN_LOWER+=("$(lower "$one")"); done
for one in "${SURNAMES[@]}"; do SURNAME_LOWER+=("$(lower "$one")"); done

# ---------------------------------------------------------------------------------------
# LDIF
# ---------------------------------------------------------------------------------------

# Written to the people entries as they go by, so the servers can name a real person.
POC_ADDRESSES=()
POC_NAMES=()

person() {
    local index="$1" uid="$2" given="$3" surname="$4" ic_email="$5" title="$6"
    local employee_type="$7" rank="$8" organization="$9" certificate="${10}"
    local phone=$((index % 10000))
    while [ "${#phone}" -lt 4 ]; do phone="0$phone"; done
    printf '%s\n' "\
dn: uid=$uid,ou=people,$BASE_DN
objectClass: top
objectClass: person
objectClass: organizationalPerson
objectClass: inetOrgPerson
objectClass: icOrgPerson
uid: $uid
userPassword: password
cn: $given $surname
sn: $surname
givenName: $given
displayName: $given $surname
icEmail: $ic_email
internetEmail: $uid@ugov.gov
title: $title
telephoneNumber: +1 555 $phone
employeeType: $employee_type
countryOfAffiliation: USA
dutyOrganization: $organization
adminOrganization: $organization
isICMember: TRUE
icNetworks: JWICS
resourceSecurityMark: UNCLASSIFIED
o: $organization
ou: people"
    [ -n "$rank" ] && echo "rank: $rank"
    [ -n "$certificate" ] && echo "userCertificate;binary:: $certificate"
    echo
}

server() {
    local index="$1" cn="$2" poc="$3" ato="$4" lifecycle="$5" organization="$6"
    local certificate="$7"
    printf '%s\n' "\
dn: cn=$cn,ou=servers,$BASE_DN
objectClass: top
objectClass: icOrgServer
cn: $cn
uid: $cn
givenName: $cn
description: $cn application host
serverURL: https://$cn.example.ic.gov
icServerAddress: 10.$(( index / 65025 % 250 + 1 )).$(( index / 255 % 250 + 1 )).$(( index % 250 + 1 ))
ATOStatus: $ato
lifeCycleStatus: $lifecycle
employeeType: NPE
countryOfAffiliation: USA
dutyOrganization: $organization
adminOrganization: $organization
isICMember: TRUE
icNetworks: JWICS
resourceSecurityMark: UNCLASSIFIED
serverPOC: $poc
o: $organization
ou: servers"
    [ -n "$certificate" ] && echo "userCertificate;binary:: $certificate"
    echo
}

# ---------------------------------------------------------------------------------------

if [ "$CERTIFICATES" = true ]; then
    setup_ca
fi

log "Generating $USERS people and $SERVERS servers under $BASE_DN"

cat <<ENTRY
# Generated by scripts/generate-directory-data.sh - dummy data, not for any real directory.
# Every person here has the password "password".

dn: ou=people,$BASE_DN
objectClass: top
objectClass: organizationalUnit
ou: people

dn: ou=servers,$BASE_DN
objectClass: top
objectClass: organizationalUnit
ou: servers

ENTRY

# The four the README names, with the same addresses and expiry states as the embedded
# sample, so a walk-through reads the same whichever directory is behind it.
if [ "$USERS" -ge 1 ]; then
    person 0 alice Alice Archer alice@intelink.ic.gov "Systems Engineer" Civilian "" "Example Agency" \
        "$(certificate_for 0 alice@intelink.ic.gov email:alice@intelink.ic.gov)"
    POC_ADDRESSES+=(alice@intelink.ic.gov); POC_NAMES+=("Alice Archer")
fi
if [ "$USERS" -ge 2 ]; then
    person 1 bwilson Bob Wilson bob.wilson@intelink.ic.gov "Database Administrator" Civilian "" "Example Agency" \
        "$(certificate_for 3 bob.wilson@intelink.ic.gov email:bob.wilson@intelink.ic.gov)"
    POC_ADDRESSES+=(bob.wilson@intelink.ic.gov); POC_NAMES+=("Bob Wilson")
fi
if [ "$USERS" -ge 3 ]; then
    person 2 cchase Carol Chase carol.chase@intelink.ic.gov "Security Officer" Military Major "Example Agency" \
        "$(certificate_for 1 carol.chase@intelink.ic.gov email:carol.chase@intelink.ic.gov)"
    POC_ADDRESSES+=(carol.chase@intelink.ic.gov); POC_NAMES+=("Carol Chase")
fi
if [ "$USERS" -ge 4 ]; then
    person 3 dday Dana Day dana.day@intelink.ic.gov Analyst Contractor "" "Example Agency" ""
    POC_ADDRESSES+=(dana.day@intelink.ic.gov); POC_NAMES+=("Dana Day")
fi

index=4
while [ "$index" -lt "$USERS" ]; do
    first=$((index % ${#GIVEN_NAMES[@]}))
    # Divided by the length of the given names, so every combination of the two is used
    # before any repeats. Past that they do repeat, as names do in a real directory.
    last=$(((index / ${#GIVEN_NAMES[@]}) % ${#SURNAMES[@]}))
    given="${GIVEN_NAMES[$first]}"
    surname="${SURNAMES[$last]}"
    # The index keeps these unique once the two name tables start repeating.
    uid="${GIVEN_LOWER[$first]:0:1}${SURNAME_LOWER[$last]}$index"
    ic_email="${GIVEN_LOWER[$first]}.${SURNAME_LOWER[$last]}$index@intelink.ic.gov"
    employee_type="${EMPLOYEE_TYPES[$((index % 3))]}"
    person "$index" "$uid" "$given" "$surname" "$ic_email" \
        "${TITLES[$((index % ${#TITLES[@]}))]}" "$employee_type" \
        "${RANKS[$((index % 3))]}" "${ORGANIZATIONS[$((index % ${#ORGANIZATIONS[@]}))]}" \
        "$(certificate_for "$index" "$ic_email" "email:$ic_email")"
    POC_ADDRESSES+=("$ic_email"); POC_NAMES+=("$given $surname")
    index=$((index + 1))
    [ $((index % 500)) -eq 0 ] && log "  ...$index people"
done

# A point of contact is an address for most servers and a person's name for the rest, and
# for every ninth an organization that matches nobody - which is how a server ends up with
# no owner to tell about an expiring certificate.
poc_for() {
    local index="$1" pool owner
    # Contacts are drawn from the first fifth of the people, so that some of them own
    # several servers. A directory where every server has a different contact makes the
    # "servers this person is responsible for" filter look like it does nothing.
    pool=$(( ${#POC_ADDRESSES[@]} / 5 ))
    if [ "$pool" -lt 4 ]; then
        pool=4
    fi
    if [ "$pool" -gt ${#POC_ADDRESSES[@]} ]; then
        pool=${#POC_ADDRESSES[@]}
    fi
    owner=$((index % pool))
    if [ $((index % 9)) -eq 8 ]; then
        printf '%s' "Example Agency Operations"
    elif [ $((index % 3)) -eq 2 ]; then
        printf '%s' "${POC_NAMES[$owner]}"
    else
        printf '%s' "${POC_ADDRESSES[$owner]}"
    fi
}

index=0
while [ "$index" -lt "$SERVERS" ]; do
    number=$((index / ${#ROLES[@]} + 1))
    while [ "${#number}" -lt 2 ]; do number="0$number"; done
    name="${ROLES[$((index % ${#ROLES[@]}))]}$number"
    server "$index" "$name" "$(poc_for "$index")" \
        "${ATO_STATUS[$((index % ${#ATO_STATUS[@]}))]}" \
        "${LIFECYCLE[$((index % ${#LIFECYCLE[@]}))]}" \
        "${ORGANIZATIONS[$((index % ${#ORGANIZATIONS[@]}))]}" \
        "$(certificate_for "$index" "$name.example.ic.gov" "DNS:$name.example.ic.gov")"
    index=$((index + 1))
    [ $((index % 500)) -eq 0 ] && log "  ...$index servers"
done

log "Done"

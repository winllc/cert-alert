package com.winllc.certalert.demo;

import com.unboundid.ldap.sdk.Entry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The directory a demo shows: invented people, invented servers, real certificates.
 *
 * <p>Built to exercise what the application is for rather than to look tidy. Every state
 * it reports on is present, because a demo where nothing is wrong demonstrates nothing:
 * certificates good for years, ones inside the warning window, ones inside the critical
 * one, ones that lapsed weeks ago, and entries publishing nothing at all.
 *
 * <p>People hold two certificates, as they do in a PKI for people - one that signs, one
 * that is encrypted to - and some hold a pair straddling two issuances, which is the
 * ordinary way to end up half expired. Servers name their points of contact both as an
 * address and as a person's name, because the specification defines {@code serverPOC} as a
 * name and real directories do both; the join has to land on the same person either way.
 *
 * <p>Seeded, so a demo restarted an hour later is the same demo, and dated from startup,
 * so it is never a directory that has rotted.
 */
final class DemoDirectoryData {

    private static final String[] GIVEN_NAMES = {
        "Alice", "Bob", "Carol", "Dana", "Erin", "Frank", "Grace", "Hector", "Iris", "Jamal",
        "Kira", "Liam", "Maya", "Noor", "Omar", "Priya", "Quinn", "Rosa", "Sam", "Tara",
        "Umar", "Vera", "Wes", "Xenia"
    };

    private static final String[] FAMILY_NAMES = {
        "Archer", "Wilson", "Chase", "Day", "Frost", "Mensah", "Oyelaran", "Novak", "Haddad",
        "Fitzgerald", "Nakamura", "Okafor", "Silva", "Petrov", "Larsen", "Duval", "Bhatt",
        "Moreau", "Kowalski", "Ibrahim", "Reyes", "Sandoval", "Tan", "Weaver"
    };

    private static final String[] TITLES = {
        "Systems Engineer", "Security Analyst", "Network Engineer", "Database Administrator",
        "Programme Manager", "Site Reliability Engineer", "Information Assurance Officer"
    };

    private static final String[] SUB_ORGANISATIONS = {
        "Enterprise IT", "Mission Systems", "Cyber Defence", "Data Services", "Field Support"
    };

    private static final String[] SERVER_ROLES = {
        "application host", "database host", "message broker", "cache node", "directory replica",
        "reverse proxy", "file gateway", "monitoring collector"
    };

    private static final String[] LIFECYCLE = {"Production", "Production", "Production", "Test"};

    /**
     * How long a certificate has left, and how often that turns up.
     *
     * <p>Weighted well towards healthy, because a directory where most things are on fire
     * reads as a broken fixture rather than a busy estate - but with enough of every other
     * state that the tables, the round-up and the metrics all have something to say.
     *
     * <p>Dealt rather than drawn. Sixteen servers drawing independently routinely came up
     * with nothing expired and nothing missing a certificate at all, which is a demo
     * quietly not showing two of the things it exists to show. A deck holds one of every
     * state before it holds any duplicates, so a small directory still covers them.
     */
    private enum Health {
        YEARS,
        EXPIRING_SOON,
        CRITICAL,
        EXPIRED,
        NONE
    }

    private static final Health[] SPREAD = {
        Health.YEARS, Health.YEARS, Health.YEARS, Health.YEARS, Health.YEARS, Health.YEARS,
        Health.YEARS, Health.YEARS, Health.YEARS, Health.YEARS, Health.YEARS, Health.YEARS,
        Health.EXPIRING_SOON, Health.EXPIRING_SOON, Health.EXPIRING_SOON,
        Health.CRITICAL,
        Health.EXPIRED,
        Health.NONE
    };

    /**
     * Coprime with the spread's length, so stepping by it visits every slot before
     * repeating any - which is what makes a short fill proportional rather than blocky.
     */
    private static final int STRIDE = 7;

    private final String baseDn;
    private final String peopleDn;
    private final String serversDn;
    private final DemoCertificates certificates;
    private final Instant now;
    private final Random random;
    private final String password;
    private final List<Entry> seededAccounts;

    DemoDirectoryData(
            String baseDn,
            DemoCertificates certificates,
            Instant now,
            long seed,
            String password,
            List<Entry> seededAccounts) {
        this.baseDn = baseDn;
        this.peopleDn = "ou=people," + baseDn;
        this.serversDn = "ou=servers," + baseDn;
        this.certificates = certificates;
        this.now = now;
        this.random = new Random(seed);
        this.password = password;
        this.seededAccounts = seededAccounts;
    }

    /** Every entry, structure first, in the order a directory would hold them. */
    List<Entry> entries(int people, int servers) {
        List<Entry> entries = new ArrayList<>();
        entries.add(structural(baseDn, "domain", "dc", firstComponent()));
        entries.add(structural(peopleDn, "organizationalUnit", "ou", "people"));
        entries.add(structural(serversDn, "organizationalUnit", "ou", "servers"));

        List<String> addresses = new ArrayList<>();
        List<String> names = new ArrayList<>();

        // The accounts a visitor signs in as come from the seed rather than from here:
        // they are directory data, and what they carry is what the sign-in page prints.
        // Certificates are minted for them here, because those have to be dated from now.
        //
        // All of them are given something expiring soon, so whichever one a visitor picks,
        // the pages that are about the person reading them have something on them rather
        // than being empty in a way that looks like a fault.
        for (Entry seeded : seededAccounts) {
            entries.add(withCredentials(seeded));
            addresses.add(seeded.getAttributeValue("icEmail"));
            names.add(seeded.getAttributeValue("displayName"));
        }

        List<Health> peopleHealth = deal(people);
        for (int i = 0; i < people; i++) {
            String given = GIVEN_NAMES[i % GIVEN_NAMES.length];
            String family = FAMILY_NAMES[(i * 7 + 3) % FAMILY_NAMES.length];
            String uid = (given.charAt(0) + family).toLowerCase(Locale.ROOT) + (i >= GIVEN_NAMES.length ? i : "");
            String displayName = given + " " + family;
            String email = uid + "@intelink.ic.gov";

            // One in six holds only half a pair - the other half renewed, or never issued.
            boolean pair = i % 6 != 0;
            entries.add(person(uid, given, family, displayName, email,
                    TITLES[i % TITLES.length], SUB_ORGANISATIONS[i % SUB_ORGANISATIONS.length],
                    peopleHealth.get(i), pair, i % 9 == 4));
            addresses.add(email);
            names.add(displayName);
        }

        List<Health> serverHealth = deal(servers);
        // The wildcard goes on the first server that actually publishes something. Pinned
        // to a fixed index it landed, often enough, on one the deal had given no
        // certificate at all - and the risky-name flag, which is the whole reason a
        // wildcard is here, then had nothing to flag.
        int wildcard = serverHealth.indexOf(
                serverHealth.stream().filter(health -> health != Health.NONE).findFirst().orElse(null));
        for (int i = 0; i < servers; i++) {
            entries.add(server(i, serverHealth.get(i), i == wildcard, addresses, names));
        }
        return entries;
    }

    /**
     * A seeded account, with certificates minted for it.
     *
     * <p>The entry arrives from the seed carrying everything about the person; what it
     * cannot carry is a certificate, because those have to be dated from the moment this
     * demo started rather than from whenever the seed was written.
     */
    private Entry withCredentials(Entry seeded) {
        Entry entry = seeded.duplicate();
        String subject = "CN=%s,OU=People,O=Example Agency,C=US".formatted(entry.getAttributeValue("displayName"));
        String email = entry.getAttributeValue("icEmail");
        Instant notBefore = notBefore(Health.EXPIRING_SOON);
        Instant notAfter = notAfter(Health.EXPIRING_SOON);

        entry.addAttribute("userCertificate;binary", certificates.issue(
                subject, notBefore, notAfter, DemoCertificates.Use.SIGNING, List.of(email)));
        entry.addAttribute("userCertificate;binary", certificates.issue(
                subject, notBefore, notAfter, DemoCertificates.Use.ENCRYPTION, List.of(email)));
        return entry;
    }

    /**
     * A person, and the credentials they publish.
     *
     * @param pair whether they hold both halves, or only the signing one
     * @param straddles whether the two halves come from different issuances, which is how
     *     a person ends up half expired - the case most worth showing, and too important
     *     to leave to whether the dice produced one
     */
    private Entry person(
            String uid,
            String given,
            String family,
            String displayName,
            String email,
            String title,
            String subOrganisation,
            Health health,
            boolean pair,
            boolean straddles) {

        Entry entry = new Entry("uid=" + uid + "," + peopleDn);
        entry.addAttribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson", "icOrgPerson");
        entry.addAttribute("uid", uid);
        // Everyone carries the same password. It is not a secret: these people are
        // invented and the directory holding them goes with the process.
        entry.addAttribute("userPassword", password);
        entry.addAttribute("cn", displayName);
        entry.addAttribute("sn", family);
        entry.addAttribute("givenName", given);
        entry.addAttribute("displayName", displayName);
        entry.addAttribute("icEmail", email);
        entry.addAttribute("internetEmail", uid + "@ugov.gov");
        entry.addAttribute("title", title);
        entry.addAttribute("telephoneNumber", "+1 555 %04d".formatted(random.nextInt(10000)));
        entry.addAttribute("employeeType", "Civilian");
        entry.addAttribute("countryOfAffiliation", "USA");
        entry.addAttribute("dutyOrganization", "Example Agency");
        entry.addAttribute("dutySubOrganization", subOrganisation);
        entry.addAttribute("isICMember", "TRUE");

        if (health == Health.NONE) {
            return entry;
        }

        String subject = "CN=%s,OU=People,O=Example Agency,C=US".formatted(displayName);
        List<byte[]> held = new ArrayList<>();
        held.add(certificates.issue(subject, notBefore(health), notAfter(health),
                DemoCertificates.Use.SIGNING, List.of(email)));
        if (pair) {
            // The encryption half is written moments after the signing half. Renewed on
            // its own schedule, it can be a whole issuance apart from the signing half:
            // one lapsed and one current, or one renewed and one not yet.
            Health other = straddles ? (health == Health.EXPIRED ? Health.YEARS : Health.EXPIRED) : health;
            held.add(certificates.issue(subject, notBefore(other), notAfter(other),
                    DemoCertificates.Use.ENCRYPTION, List.of(email)));
        }
        held.forEach(der -> entry.addAttribute("userCertificate;binary", der));
        return entry;
    }

    /**
     * A server, its points of contact, and the certificate it serves.
     *
     * @param wildcard whether this is the one that also answers to {@code *.example.ic.gov}
     */
    private Entry server(
            int index, Health health, boolean wildcard, List<String> addresses, List<String> names) {
        String role = SERVER_ROLES[index % SERVER_ROLES.length];
        String host = "%s%02d".formatted(shortNameOf(role), (index / SERVER_ROLES.length) + 1);
        String fqdn = host + ".example.ic.gov";

        Entry entry = new Entry("cn=" + host + "," + serversDn);
        entry.addAttribute("objectClass", "top", "icOrgServer");
        entry.addAttribute("cn", host);
        entry.addAttribute("uid", host);
        entry.addAttribute("givenName", host);
        entry.addAttribute("serverURL", "https://" + fqdn);
        entry.addAttribute("icServerAddress", "10.1.%d.%d".formatted(index / 250 + 1, index % 250 + 10));
        entry.addAttribute("description", host + " " + role);
        entry.addAttribute("ATOStatus", index % 9 == 0 ? "Pending" : "Authorized");
        entry.addAttribute("lifeCycleStatus", LIFECYCLE[index % LIFECYCLE.length]);
        entry.addAttribute("employeeType", "NPE");
        entry.addAttribute("countryOfAffiliation", "USA");
        entry.addAttribute("dutyOrganization", "Example Agency");
        entry.addAttribute("dutySubOrganization", SUB_ORGANISATIONS[index % SUB_ORGANISATIONS.length]);
        entry.addAttribute("adminOrganization", "Example Agency");
        entry.addAttribute("isICMember", "TRUE");
        entry.addAttribute("icNetworks", "JWICS");
        entry.addAttribute("resourceSecurityMark", "UNCLASSIFIED");
        entry.addAttribute("o", "Example Agency");
        entry.addAttribute("ou", "servers");

        // Two of the sign-in accounts are points of contact, and one deliberately is not.
        // That is the difference the reader account exists to show: the same pages, with
        // nothing on the ones that are about you. The project administrator is not a
        // contact either - what they run a project gives them, not a serverPOC.
        List<String> contacts = new ArrayList<>();
        if (index % 3 == 0 && seededEmail("Administrator") != null) {
            contacts.add(seededEmail("Administrator"));
        }
        if (index % 4 == 2 && seededEmail("Point of contact") != null) {
            contacts.add(seededEmail("Point of contact"));
        }
        // Written both ways round on purpose: an address on some, a person's name on
        // others, and one that names a team nobody in the directory answers to.
        contacts.add(someoneOtherThanTheReader(addresses));
        if (index % 4 == 1) {
            contacts.add(someoneOtherThanTheReader(names));
        }
        if (index % 7 == 3) {
            contacts.add("duty-officer@intelink.ic.gov");
        }
        contacts.stream().distinct().forEach(contact -> entry.addAttribute("serverPOC", contact));

        if (health != Health.NONE) {
            // One carries a wildcard as well, which is what the risky-name flag reads.
            List<String> sans = wildcard ? List.of(fqdn, "*.example.ic.gov") : List.of(fqdn);
            entry.addAttribute("userCertificate;binary", certificates.issue(
                    "CN=%s,O=Example Agency,C=US".formatted(fqdn),
                    notBefore(health), notAfter(health), DemoCertificates.Use.SERVER, sans));
        }
        return entry;
    }

    /** The suffix and the two containers everything else hangs from. */
    private static Entry structural(String dn, String objectClass, String namingAttribute, String value) {
        Entry entry = new Entry(dn);
        entry.addAttribute("objectClass", "top", objectClass);
        entry.addAttribute(namingAttribute, value);
        return entry;
    }

    /**
     * Health for {@code count} entries: one of every state, then the spread, shuffled.
     *
     * <p>Guarantees the coverage a demo needs at the sizes a demo runs at, and keeps the
     * proportions once it is larger.
     */
    /**
     * Anyone from the directory but the reader.
     *
     * <p>The reader account is defined by having nothing to answer for, so a random
     * serverPOC landing on them would quietly turn them into a point of contact and the
     * sign-in page would be describing a role the demo no longer has.
     */
    private String someoneOtherThanTheReader(List<String> candidates) {
        for (int attempt = 0; attempt < 8; attempt++) {
            String candidate = candidates.get(random.nextInt(candidates.size()));
            if (!isTheReader(candidate)) {
                return candidate;
            }
        }
        String administrator = seededEmail("Administrator");
        return administrator != null ? administrator : candidates.getFirst();
    }

    /** Whether this names the account the seed marks as answering for nothing. */
    private boolean isTheReader(String candidate) {
        Entry reader = seededWithRole("Reader");
        if (reader == null) {
            return false;
        }
        return candidate.equalsIgnoreCase(reader.getAttributeValue("icEmail"))
                || candidate.equalsIgnoreCase(reader.getAttributeValue("displayName"));
    }

    /** The address of the seeded account carrying this role, or null where there is none. */
    private String seededEmail(String role) {
        Entry entry = seededWithRole(role);
        return entry == null ? null : entry.getAttributeValue("icEmail");
    }

    private Entry seededWithRole(String role) {
        return seededAccounts.stream()
                .filter(entry -> role.equalsIgnoreCase(entry.getAttributeValue(DemoAccounts.ROLE)))
                .findFirst()
                .orElse(null);
    }

    private List<Health> deal(int count) {
        List<Health> deck = new ArrayList<>(count);
        if (count >= Health.values().length) {
            deck.addAll(List.of(Health.values()));
        }
        // Strides across the spread rather than walking it, because the spread is written
        // in blocks: taken in order, the first dozen are all healthy, and a directory of
        // sixteen servers would be filled entirely out of that block.
        for (int i = 0; deck.size() < count; i++) {
            deck.add(SPREAD[(i * STRIDE) % SPREAD.length]);
        }
        Collections.shuffle(deck, random);
        return deck;
    }

    private Instant notBefore(Health health) {
        return switch (health) {
            case YEARS -> now.minus(Duration.ofDays(120));
            case EXPIRING_SOON, CRITICAL -> now.minus(Duration.ofDays(365 - 20));
            case EXPIRED -> now.minus(Duration.ofDays(400));
            case NONE -> now;
        };
    }

    private Instant notAfter(Health health) {
        return switch (health) {
            case YEARS -> now.plus(Duration.ofDays(400 + random.nextInt(600)));
            case EXPIRING_SOON -> now.plus(Duration.ofDays(8 + random.nextInt(21)));
            case CRITICAL -> now.plus(Duration.ofDays(1 + random.nextInt(6)));
            case EXPIRED -> now.minus(Duration.ofDays(1 + random.nextInt(60)));
            case NONE -> now;
        };
    }

    private static String shortNameOf(String role) {
        return switch (role) {
            case "application host" -> "web";
            case "database host" -> "db";
            case "message broker" -> "mq";
            case "cache node" -> "cache";
            case "directory replica" -> "ldap";
            case "reverse proxy" -> "proxy";
            case "file gateway" -> "files";
            default -> "mon";
        };
    }

    /** {@code dc=example,dc=test} names its first component {@code example}. */
    private String firstComponent() {
        String first = baseDn.split(",")[0];
        int equals = first.indexOf('=');
        return equals < 0 ? first : first.substring(equals + 1);
    }
}

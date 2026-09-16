package com.winllc.certalert.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Regenerates the sample directory used by the {@code dev} profile, in IC FSD shape.
 *
 * <p>Disabled by default: it writes into {@code src/main/resources}. Re-enable and run it
 * when the sample needs refreshing, for instance once the baked-in expiry dates have aged
 * past the point of being interesting.
 */
@Disabled("Run by hand to regenerate src/main/resources/dev-directory.ldif")
class DevLdifGenerator {

    private static final Path TARGET = Path.of("src/main/resources/dev-directory.ldif");
    private static final long DAY = 86400L;

    @Test
    void generate() throws IOException {
        Instant now = Instant.now();
        StringBuilder ldif = new StringBuilder(header());

        ldif.append(person("alice", "Alice Archer", "alice@intelink.ic.gov", "Systems Engineer", "Civilian",
                cert("alice@intelink.ic.gov", now.minusSeconds(30 * DAY), now.plusSeconds(3650 * DAY))));
        ldif.append(person("bwilson", "Bob Wilson", "bob.wilson@intelink.ic.gov", "Database Administrator", "Civilian",
                cert("bob.wilson@intelink.ic.gov", now.minusSeconds(400 * DAY), now.minusSeconds(5 * DAY))));
        ldif.append(person("cchase", "Carol Chase", "carol.chase@intelink.ic.gov", "Security Officer", "Military",
                cert("carol.chase@intelink.ic.gov", now.minusSeconds(340 * DAY), now.plusSeconds(20 * DAY))));
        ldif.append(person("dday", "Dana Day", "dana.day@intelink.ic.gov", "Analyst", "Contractor"));

        // serverPOC by address for two of these, and by name for the third, because real
        // directories do both and the join has to cope with either.
        ldif.append(server("web01", "https://web01.example.ic.gov", "10.1.2.11",
                List.of("alice@intelink.ic.gov"), "Authorized", "Production",
                cert("web01.example.ic.gov", now.minusSeconds(30 * DAY), now.plusSeconds(1825 * DAY))));
        ldif.append(server("web02", "https://web02.example.ic.gov", "10.1.2.12",
                List.of("alice@intelink.ic.gov", "bob.wilson@intelink.ic.gov"), "Authorized", "Production",
                cert("web02.example.ic.gov", now.minusSeconds(400 * DAY), now.minusSeconds(2 * DAY))));
        ldif.append(server("db01", "https://db01.example.ic.gov", "10.1.2.20",
                List.of("Bob Wilson"), "Authorized", "Production",
                cert("db01.example.ic.gov", now.minusSeconds(340 * DAY), now.plusSeconds(25 * DAY))));
        ldif.append(server("cache01", "https://cache01.example.ic.gov", "10.1.2.30",
                List.of("carol.chase@intelink.ic.gov"), "In Process", "Development"));

        Files.writeString(TARGET, ldif.toString());
    }

    private String header() {
        return """
                dn: dc=example,dc=test
                objectClass: top
                objectClass: domain
                dc: example

                dn: ou=people,dc=example,dc=test
                objectClass: top
                objectClass: organizationalUnit
                ou: people

                dn: ou=servers,dc=example,dc=test
                objectClass: top
                objectClass: organizationalUnit
                ou: servers

                """;
    }

    private String person(
            String uid, String name, String icEmail, String title, String employeeType, String... certificates) {
        StringBuilder entry = new StringBuilder();
        entry.append("dn: uid=").append(uid).append(",ou=people,dc=example,dc=test\n");
        entry.append("objectClass: top\n");
        entry.append("objectClass: person\n");
        entry.append("objectClass: organizationalPerson\n");
        entry.append("objectClass: inetOrgPerson\n");
        entry.append("objectClass: icOrgPerson\n");
        entry.append("uid: ").append(uid).append('\n');
        entry.append("cn: ").append(name).append('\n');
        entry.append("sn: ").append(name.substring(name.lastIndexOf(' ') + 1)).append('\n');
        entry.append("givenName: ").append(name.substring(0, name.indexOf(' '))).append('\n');
        entry.append("displayName: ").append(name).append('\n');
        entry.append("icEmail: ").append(icEmail).append('\n');
        entry.append("internetEmail: ").append(uid).append("@ugov.gov\n");
        entry.append("title: ").append(title).append('\n');
        entry.append("telephoneNumber: +1 555 0100\n");
        entry.append("employeeType: ").append(employeeType).append('\n');
        entry.append("countryOfAffiliation: USA\n");
        entry.append("dutyOrganization: Example Agency\n");
        entry.append("adminOrganization: Example Agency\n");
        entry.append("isICMember: TRUE\n");
        entry.append("icNetworks: JWICS\n");
        entry.append("resourceSecurityMark: UNCLASSIFIED\n");
        entry.append("o: Example Agency\n");
        entry.append("ou: people\n");
        for (String certificate : certificates) {
            entry.append("userCertificate;binary:: ").append(certificate).append('\n');
        }
        return entry.append('\n').toString();
    }

    private String server(
            String cn,
            String serverUrl,
            String address,
            List<String> pocs,
            String atoStatus,
            String lifeCycleStatus,
            String... certificates) {
        StringBuilder entry = new StringBuilder();
        entry.append("dn: cn=").append(cn).append(",ou=servers,dc=example,dc=test\n");
        entry.append("objectClass: top\n");
        entry.append("objectClass: icOrgServer\n");
        entry.append("cn: ").append(cn).append('\n');
        entry.append("uid: ").append(cn).append('\n');
        entry.append("givenName: ").append(cn).append('\n');
        entry.append("serverURL: ").append(serverUrl).append('\n');
        entry.append("icServerAddress: ").append(address).append('\n');
        entry.append("description: ").append(cn).append(" application host\n");
        entry.append("ATOStatus: ").append(atoStatus).append('\n');
        entry.append("lifeCycleStatus: ").append(lifeCycleStatus).append('\n');
        entry.append("employeeType: NPE\n");
        entry.append("countryOfAffiliation: USA\n");
        entry.append("dutyOrganization: Example Agency\n");
        entry.append("adminOrganization: Example Agency\n");
        entry.append("isICMember: TRUE\n");
        entry.append("icNetworks: JWICS\n");
        entry.append("resourceSecurityMark: UNCLASSIFIED\n");
        entry.append("o: Example Agency\n");
        entry.append("ou: servers\n");
        pocs.forEach(poc -> entry.append("serverPOC: ").append(poc).append('\n'));
        for (String certificate : certificates) {
            entry.append("userCertificate;binary:: ").append(certificate).append('\n');
        }
        return entry.append('\n').toString();
    }

    /** LDIF carries binary values as base64 on a {@code ::} line. */
    private String cert(String commonName, Instant notBefore, Instant notAfter) {
        return Base64.getEncoder().encodeToString(TestCertificates.der(commonName, notBefore, notAfter));
    }

    @SuppressWarnings("unused")
    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}

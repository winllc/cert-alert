package com.winllc.certalert.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Regenerates the sample directory used by the {@code dev} profile.
 *
 * <p>Disabled by default: it writes into {@code src/main/resources}. Re-enable and run it
 * when the sample data needs refreshing, for instance once the baked-in expiry dates have
 * aged past the point of being interesting.
 */
@Disabled("Run by hand to regenerate src/main/resources/dev-directory.ldif")
class DevLdifGenerator {

    private static final Path TARGET = Path.of("src/main/resources/dev-directory.ldif");

    @Test
    void generate() throws IOException {
        Instant now = Instant.now();
        StringBuilder ldif = new StringBuilder();

        ldif.append(header());

        ldif.append(person("alice", "Alice Archer", "alice@example.gov", "Systems Engineer",
                cert("alice@example.gov", now.minusSeconds(86400L * 30), now.plusSeconds(86400L * 3650))));
        ldif.append(person("bwilson", "Bob Wilson", "bob.wilson@example.gov", "Database Administrator",
                cert("bob.wilson@example.gov", now.minusSeconds(86400L * 400), now.minusSeconds(86400L * 5))));
        ldif.append(person("cchase", "Carol Chase", "carol.chase@example.gov", "Security Officer",
                cert("carol.chase@example.gov", now.minusSeconds(86400L * 340), now.plusSeconds(86400L * 20))));
        ldif.append(person("dday", "Dana Day", "dana.day@example.gov", "Analyst"));

        ldif.append(device("web01", "web01.example.gov", List.of("alice@example.gov"),
                cert("web01.example.gov", now.minusSeconds(86400L * 30), now.plusSeconds(86400L * 1825))));
        ldif.append(device("web02", "web02.example.gov",
                List.of("alice@example.gov", "bob.wilson@example.gov"),
                cert("web02.example.gov", now.minusSeconds(86400L * 400), now.minusSeconds(86400L * 2))));
        ldif.append(device("db01", "db01.example.gov", List.of("bob.wilson@example.gov"),
                cert("db01.example.gov", now.minusSeconds(86400L * 340), now.plusSeconds(86400L * 25))));
        ldif.append(device("cache01", "cache01.example.gov", List.of("carol.chase@example.gov")));

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

    private String person(String uid, String name, String email, String title, String... certificates) {
        StringBuilder entry = new StringBuilder();
        entry.append("dn: uid=").append(uid).append(",ou=people,dc=example,dc=test\n");
        entry.append("objectClass: top\n");
        entry.append("objectClass: person\n");
        entry.append("objectClass: organizationalPerson\n");
        entry.append("objectClass: inetOrgPerson\n");
        entry.append("uid: ").append(uid).append('\n');
        entry.append("cn: ").append(name).append('\n');
        entry.append("sn: ").append(name.substring(name.lastIndexOf(' ') + 1)).append('\n');
        entry.append("givenName: ").append(name.substring(0, name.indexOf(' '))).append('\n');
        entry.append("displayName: ").append(name).append('\n');
        entry.append("mail: ").append(email).append('\n');
        entry.append("title: ").append(title).append('\n');
        entry.append("telephoneNumber: +1 555 0100\n");
        entry.append("employeeType: Civilian\n");
        entry.append("c: US\n");
        entry.append("o: Example Agency\n");
        entry.append("ou: people\n");
        for (String certificate : certificates) {
            entry.append("userCertificate;binary:: ").append(certificate).append('\n');
        }
        return entry.append('\n').toString();
    }

    private String device(String cn, String fqdn, List<String> pocs, String... certificates) {
        StringBuilder entry = new StringBuilder();
        entry.append("dn: cn=").append(cn).append(",ou=servers,dc=example,dc=test\n");
        entry.append("objectClass: top\n");
        entry.append("objectClass: device\n");
        entry.append("cn: ").append(cn).append('\n');
        entry.append("associatedDomain: ").append(fqdn).append('\n');
        entry.append("description: ").append(cn).append(" application host\n");
        entry.append("serialNumber: SN-").append(cn.toUpperCase(java.util.Locale.ROOT)).append('\n');
        entry.append("operatingSystem: Red Hat Enterprise Linux 9\n");
        entry.append("o: Example Agency\n");
        entry.append("ou: servers\n");
        pocs.forEach(poc -> entry.append("serverPoc: ").append(poc).append('\n'));
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
    private static Duration days(long count) {
        return Duration.ofDays(count);
    }
}

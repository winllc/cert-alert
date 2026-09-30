package com.winllc.certalert.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.unboundid.ldap.sdk.Entry;
import com.winllc.certalert.domain.CertificateUse;
import com.winllc.certalert.domain.KeyUsage;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The directory a demo shows itself on.
 *
 * <p>Worth testing rather than eyeballing, because every one of these is something a demo
 * silently stops showing rather than failing over: a fixture whose dates have gone by, a
 * spread that happens to contain nothing expired, a pair whose two halves are no longer
 * told apart. None of them would break a page.
 */
class DemoDirectoryDataTest {

    private static final String BASE_DN = "dc=example,dc=test";

    private static Instant now;
    private static List<Entry> entries;
    private static List<Entry> seeded;

    @BeforeAll
    static void generate() {
        now = Instant.now();
        seeded = DemoAccountSeed.read(BASE_DN, "password");
        entries = new DemoDirectoryData(BASE_DN, new DemoCertificates(), now, 20260101L, "password", seeded)
                .entries(24, 16);
    }

    @Test
    void holdsTheSuffixItsContainersAndEverythingAsked() {
        assertThat(dnsOf(entries)).contains(BASE_DN, "ou=people," + BASE_DN, "ou=servers," + BASE_DN);
        // The twenty-four asked for, and everyone the seed defines.
        assertThat(seeded).isNotEmpty();
        assertThat(people()).hasSize(24 + seeded.size());
        assertThat(servers()).hasSize(16);
    }

    /**
     * The seed defines the accounts, and every one of them reaches the directory with what
     * the sign-in page needs to print - and with credentials, which the seed cannot carry
     * because they have to be dated from now.
     */
    @Test
    void everyAccountTheSeedDefinesIsInTheDirectory() {
        for (Entry account : seeded) {
            Entry entry = people().stream()
                    .filter(person -> person.getDN().equals(account.getDN()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("No entry for " + account.getDN()));

            assertThat(entry.getAttributeValue(DemoAccounts.ROLE)).isNotBlank();
            assertThat(entry.getAttributeValue(DemoAccounts.SUMMARY)).isNotBlank();
            assertThat(entry.getAttributeValue("displayName")).isNotBlank();
            assertThat(entry.getAttributeValue("userPassword")).isEqualTo("password");
            assertThat(entry.getAttribute("userCertificate;binary")).isNotNull();
        }
    }

    /** The substitutions the seed leaves for the demo to fill in are actually filled in. */
    @Test
    void theSeedIsHungUnderTheConfiguredSuffixWithTheConfiguredPassword() {
        List<Entry> elsewhere = DemoAccountSeed.read("dc=other,dc=test", "hunter2");
        assertThat(elsewhere).isNotEmpty();
        assertThat(elsewhere).allSatisfy(entry -> {
            assertThat(entry.getDN()).endsWith("dc=other,dc=test");
            assertThat(entry.getAttributeValue("userPassword")).isEqualTo("hunter2");
        });
    }

    /**
     * Two of them answer for servers and one deliberately does not - which is the difference
     * the sign-in page describes, and the reason the reader account exists.
     */
    @Test
    void theRolesTheSeedDescribesAreRealInTheDirectory() {
        assertThat(serversContacting(emailOf("Administrator"))).isGreaterThan(1);
        assertThat(serversContacting(emailOf("Point of contact"))).isGreaterThan(1);

        // The reader answers for nothing; that is the whole of the role.
        assertThat(serversContacting(emailOf("Reader"))).isZero();
        assertThat(serversContacting(displayNameOf("Reader"))).isZero();
    }

    private static String emailOf(String role) {
        return withRole(role).getAttributeValue("icEmail");
    }

    private static String displayNameOf(String role) {
        return withRole(role).getAttributeValue("displayName");
    }

    private static Entry withRole(String role) {
        return seeded.stream()
                .filter(entry -> role.equalsIgnoreCase(entry.getAttributeValue(DemoAccounts.ROLE)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The seed defines no " + role));
    }

    private static long serversContacting(String identifier) {
        return servers().stream()
                .filter(server -> server.getAttributeValues("serverPOC") != null
                        && Arrays.stream(server.getAttributeValues("serverPOC"))
                                .anyMatch(identifier::equalsIgnoreCase))
                .count();
    }

    /**
     * Dated from the moment it is generated, never from when it was written. An image
     * built in the spring is still a demo in the autumn.
     */
    @Test
    void everyCertificateIsDatedFromNow() {
        List<X509Certificate> all = certificatesOf(entries);
        assertThat(all).isNotEmpty();
        assertThat(all).allSatisfy(certificate -> assertThat(certificate.getNotBefore().toInstant())
                .isAfter(now.minus(Duration.ofDays(500))));
        // The furthest out is a couple of years, not a decade: a fixture dated to 2099
        // never enters any of the states this application reports on.
        assertThat(all).allSatisfy(certificate -> assertThat(certificate.getNotAfter().toInstant())
                .isBefore(now.plus(Duration.ofDays(1100))));
    }

    /**
     * Every state, at the sizes a demo actually runs at. Drawn independently, sixteen
     * servers regularly came up with nothing expired and nothing without a certificate -
     * two of the things the demo exists to show, quietly absent.
     */
    @Test
    void coversEveryStateAtDemoSize() {
        assertThat(expired(certificatesOf(servers()))).isNotEmpty();
        assertThat(expiringWithin(certificatesOf(servers()), Duration.ofDays(30))).isNotEmpty();
        assertThat(servers().stream().filter(entry -> entry.getAttribute("userCertificate;binary") == null))
                .isNotEmpty();

        assertThat(expired(certificatesOf(people()))).isNotEmpty();
        assertThat(expiringWithin(certificatesOf(people()), Duration.ofDays(30))).isNotEmpty();
        assertThat(people().stream().filter(entry -> entry.getAttribute("userCertificate;binary") == null))
                .isNotEmpty();
    }

    /** And not so many that the demo reads as an estate on fire rather than a busy one. */
    @Test
    void leavesMostOfItHealthy() {
        List<X509Certificate> serverCertificates = certificatesOf(servers());
        assertThat(expiringWithin(serverCertificates, Duration.ofDays(30)).size() + expired(serverCertificates).size())
                .isLessThan(serverCertificates.size() / 2);
    }

    /**
     * The two halves of a person's credentials have to be told apart, or the pair is one
     * certificate superseding the other and half of everything expiring goes unreported.
     */
    @Test
    void aPersonHoldsASigningHalfAndAnEncryptionHalf() {
        List<Entry> paired = people().stream()
                .filter(entry -> entry.getAttribute("userCertificate;binary") != null
                        && entry.getAttribute("userCertificate;binary").getValueByteArrays().length == 2)
                .toList();
        assertThat(paired).isNotEmpty();

        Set<CertificateUse> uses = certificatesOf(List.of(paired.getFirst())).stream()
                .map(DemoDirectoryDataTest::useOf)
                .collect(Collectors.toSet());
        assertThat(uses).containsExactlyInAnyOrder(CertificateUse.SIGNING, CertificateUse.ENCRYPTION);
    }

    /** A pair straddling two issuances: the case where somebody is half expired. */
    @Test
    void somebodyIsHalfExpired() {
        boolean halfExpired = people().stream().anyMatch(entry -> {
            List<X509Certificate> held = certificatesOf(List.of(entry));
            return held.size() == 2
                    && held.stream().anyMatch(DemoDirectoryDataTest::hasExpired)
                    && held.stream().anyMatch(certificate -> !hasExpired(certificate));
        });
        assertThat(halfExpired).isTrue();
    }

    /** Servers name themselves, which is what the risky-name flags read. */
    @Test
    void serverCertificatesCarryTheirDnsNameAndOneCarriesAWildcard() throws Exception {
        List<String> names = new ArrayList<>();
        for (X509Certificate certificate : certificatesOf(servers())) {
            if (certificate.getSubjectAlternativeNames() != null) {
                certificate.getSubjectAlternativeNames().stream()
                        .filter(name -> Integer.valueOf(2).equals(name.get(0)))
                        .forEach(name -> names.add(String.valueOf(name.get(1))));
            }
        }
        assertThat(names).isNotEmpty();
        assertThat(names).anyMatch(name -> name.startsWith("*."));
    }

    /** The same seed is the same demo, so a restart is not a different directory. */
    @Test
    void theSameSeedGeneratesTheSameDirectory() {
        List<Entry> again =
                new DemoDirectoryData(BASE_DN, new DemoCertificates(), now, 20260101L, "password", seeded)
                        .entries(24, 16);
        assertThat(dnsOf(again)).isEqualTo(dnsOf(entries));
    }

    // --- helpers -------------------------------------------------------------------------

    private static List<String> dnsOf(List<Entry> entries) {
        return entries.stream().map(Entry::getDN).toList();
    }

    private static List<Entry> people() {
        return entries.stream().filter(entry -> entry.getDN().startsWith("uid=")).toList();
    }

    private static List<Entry> servers() {
        return entries.stream().filter(entry -> entry.getDN().startsWith("cn=")).toList();
    }

    private static List<X509Certificate> certificatesOf(List<Entry> from) {
        List<X509Certificate> certificates = new ArrayList<>();
        for (Entry entry : from) {
            if (entry.getAttribute("userCertificate;binary") == null) {
                continue;
            }
            for (byte[] der : entry.getAttribute("userCertificate;binary").getValueByteArrays()) {
                certificates.add(parse(der));
            }
        }
        return certificates;
    }

    private static X509Certificate parse(byte[] der) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception e) {
            throw new IllegalStateException("The generator produced something that is not a certificate", e);
        }
    }

    private static CertificateUse useOf(X509Certificate certificate) {
        return CertificateUse.from(KeyUsage.of(certificate.getKeyUsage()));
    }

    private static boolean hasExpired(X509Certificate certificate) {
        return certificate.getNotAfter().toInstant().isBefore(now);
    }

    private static List<X509Certificate> expired(List<X509Certificate> certificates) {
        return certificates.stream().filter(DemoDirectoryDataTest::hasExpired).toList();
    }

    private static List<X509Certificate> expiringWithin(List<X509Certificate> certificates, Duration window) {
        Instant horizon = now.plus(window);
        return certificates.stream()
                .filter(certificate -> !hasExpired(certificate)
                        && certificate.getNotAfter().toInstant().isBefore(horizon))
                .toList();
    }
}

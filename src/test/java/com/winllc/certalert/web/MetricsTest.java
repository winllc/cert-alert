package com.winllc.certalert.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateRisk;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.CachedCertificateRepository;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The aggregates behind the metrics page.
 *
 * <p>All of them are grouped counts done by the database, including the ones that bucket by
 * month - so this is also where it is established that the date bucketing works on the
 * database the tests run against, rather than only on the one in production.
 */
@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(roles = "USER")
class MetricsTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectoryUserRepository userRepository;

    @Autowired
    private DirectoryServerRepository serverRepository;

    @Autowired
    private CachedCertificateRepository certificateRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void seed() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        certificateRepository.deleteAll();
        serverRepository.deleteAll();
        userRepository.deleteAll();

        Instant now = Instant.now();
        DirectoryUser holder = new DirectoryUser("uid=holder,ou=people");
        holder.setUid("holder");
        holder.setDisplayName("Cert Holder");
        holder.refreshIdentifiers("holder@example.gov");
        // Two RSA-2048 on SHA-256, one 1024-bit on SHA-1, one EC. Issued a month ago,
        // expiring at spread-out points so the monthly series has something in it.
        holder.addCertificate(certificate("CN=a", "RSA", 2048, "SHA-256", "SHA256withRSA",
                now.minus(Duration.ofDays(30)), now.plus(Duration.ofDays(5)), CertificateStatus.EXPIRING_SOON));
        holder.addCertificate(certificate("CN=b", "RSA", 2048, "SHA-256", "SHA256withRSA",
                now.minus(Duration.ofDays(30)), now.plus(Duration.ofDays(200)), CertificateStatus.VALID));
        holder.addCertificate(certificate("CN=c", "RSA", 1024, "SHA-1", "SHA1withRSA",
                now.minus(Duration.ofDays(400)), now.minus(Duration.ofDays(10)), CertificateStatus.EXPIRED));
        holder.addCertificate(certificate("CN=d", "EC", 384, "SHA-384", "SHA384withECDSA",
                now.minus(Duration.ofDays(30)), now.plus(Duration.ofDays(60)), CertificateStatus.VALID));
        // Two of them are good for more than they should be, one of them doubly so.
        holder.getCertificates().get(0).describeNames(2, List.of(CertificateRisk.WILDCARD));
        holder.getCertificates().get(1).describeNames(
                40, List.of(CertificateRisk.WILDCARD, CertificateRisk.BROAD_WILDCARD, CertificateRisk.MANY_NAMES));
        holder.markSynced(now);
        holder.refreshCertificateSummary();
        userRepository.save(holder);

        holder.setDutyOrganization("Example Agency");
        holder.setDutySubOrganization("Enterprise IT");
        holder.setEmployeeType("Civilian");
        // One of them has been revoked, and one has been asked about and found good, so
        // the revocation counts have more than a single state in them.
        holder.getCertificates().get(0).recordRevocation(
                com.winllc.certalert.domain.RevocationStatus.REVOKED,
                com.winllc.certalert.domain.RevocationMethod.CRL,
                now.minus(Duration.ofDays(3)),
                "KEY_COMPROMISE",
                "CRL from http://crl.example.gov/ca.crl",
                now);
        holder.getCertificates().get(1).recordRevocation(
                com.winllc.certalert.domain.RevocationStatus.GOOD,
                com.winllc.certalert.domain.RevocationMethod.CRL,
                null,
                null,
                "CRL from http://crl.example.gov/ca.crl",
                now);
        userRepository.save(holder);

        DirectoryUser empty = new DirectoryUser("uid=empty,ou=people");
        empty.setUid("empty");
        empty.refreshIdentifiers("empty@example.gov");
        empty.setDutyOrganization("Example Agency");
        empty.setDutySubOrganization("Field Operations");
        empty.setEmployeeType("Contractor");
        empty.markSynced(now);
        empty.refreshCertificateSummary();
        userRepository.save(empty);
    }

    @Test
    void countsCertificatesByTheStateTheyAreIn() throws Exception {
        mockMvc.perform(get("/api/v1/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificates.total").value(4))
                .andExpect(jsonPath("$.certificates.VALID").value(2))
                .andExpect(jsonPath("$.certificates.EXPIRING_SOON").value(1))
                .andExpect(jsonPath("$.certificates.EXPIRED").value(1));
    }

    @Test
    void breaksDownTheKeysAndTheDigests() throws Exception {
        mockMvc.perform(get("/api/v1/metrics"))
                .andExpect(status().isOk())
                // Two RSA-2048, one RSA-1024, one EC-384, commonest first.
                .andExpect(jsonPath("$.keys[0].label").value("RSA 2048"))
                .andExpect(jsonPath("$.keys[0].count").value(2))
                .andExpect(jsonPath("$.keys.length()").value(3))
                .andExpect(jsonPath("$.hashes[0].algorithm").value("SHA-256"))
                .andExpect(jsonPath("$.hashes[0].count").value(2))
                .andExpect(jsonPath("$.hashes.length()").value(3))
                .andExpect(jsonPath("$.signatures.length()").value(3));
    }

    @Test
    void countsWhatIsFallingDue() throws Exception {
        mockMvc.perform(get("/api/v1/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiry.expired").value(1))
                .andExpect(jsonPath("$.expiry.next7").value(1))
                .andExpect(jsonPath("$.expiry.next30").value(1))
                .andExpect(jsonPath("$.expiry.next90").value(2))
                .andExpect(jsonPath("$.expiry.next365").value(3));
    }

    /**
     * The month buckets are the one place this asks the database to do date arithmetic, so
     * it is worth proving that they land where they should rather than that they merely
     * come back.
     */
    @Test
    void bucketsIssuedAndExpiringByMonth() throws Exception {
        YearMonth issuedIn = YearMonth.from(Instant.now().minus(Duration.ofDays(30)).atZone(ZoneOffset.UTC));
        YearMonth expiringIn = YearMonth.from(Instant.now().plus(Duration.ofDays(200)).atZone(ZoneOffset.UTC));

        mockMvc.perform(get("/api/v1/metrics"))
                .andExpect(status().isOk())
                // A year either side of today, inclusive of both ends.
                .andExpect(jsonPath("$.months.length()").value(25))
                .andExpect(jsonPath("$.months[?(@.month == '%s')].issued".formatted(issuedIn))
                        .value(org.hamcrest.Matchers.hasItem(3)))
                .andExpect(jsonPath("$.months[?(@.month == '%s')].expiring".formatted(expiringIn))
                        .value(org.hamcrest.Matchers.hasItem(1)));
    }

    @Test
    void countsTheEntriesAndTheOnesPublishingNothing() throws Exception {
        mockMvc.perform(get("/api/v1/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.users").value(2))
                .andExpect(jsonPath("$.entries.usersWithoutCertificate").value(1))
                .andExpect(jsonPath("$.entries.servers").value(0));
    }

    /** How many certificates are good for more than they should be. */
    @Test
    void countsWhatIsWorryingAboutTheNames() throws Exception {
        mockMvc.perform(get("/api/v1/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.risks.any").value(2))
                .andExpect(jsonPath("$.risks.WILDCARD").value(2))
                .andExpect(jsonPath("$.risks.BROAD_WILDCARD").value(1))
                .andExpect(jsonPath("$.risks.MANY_NAMES").value(1))
                .andExpect(jsonPath("$.risks.BARE_HOSTNAME").value(0));
    }

    /**
     * What has been issued, which is a different question from what is expiring - and the
     * one that says whether a renewal programme has started or a policy has changed.
     */
    @Test
    void countsWhatHasBeenIssuedAndForHowLong() throws Exception {
        mockMvc.perform(get("/api/v1/metrics"))
                .andExpect(status().isOk())
                // Three issued a month ago; the fourth is from last year.
                .andExpect(jsonPath("$.issuance.last30Days").value(0))
                .andExpect(jsonPath("$.issuance.last90Days").value(3))
                .andExpect(jsonPath("$.issuance.last365Days").value(3))
                // The database works this one out, so it is worth proving it comes back
                // with the right answer rather than merely coming back: 35, 230 and 90
                // days of validity, averaged.
                .andExpect(jsonPath("$.issuance.averageValidityDays").value(118))
                .andExpect(jsonPath("$.issuance.issuers.length()").value(1))
                .andExpect(jsonPath("$.issuance.issuers[0].count").value(4));
    }

    @Test
    void countsWhatTheAuthoritiesHaveSaid() throws Exception {
        mockMvc.perform(get("/api/v1/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revocation.byStatus.REVOKED").value(1))
                .andExpect(jsonPath("$.revocation.byStatus.GOOD").value(1))
                // Not checked is its own number, and not the same as not revoked.
                .andExpect(jsonPath("$.revocation.byStatus.NOT_CHECKED").value(2))
                .andExpect(jsonPath("$.revocation.revokedLast30Days").value(1))
                .andExpect(jsonPath("$.revocation.reasons[0].name").value("KEY_COMPROMISE"))
                .andExpect(jsonPath("$.revocation.oldestCheck").isNotEmpty());
    }

    /**
     * Where the estate is, at the level somebody actually answers for it. An agency-level
     * count says "Example Agency holds everything", which is true and of no use.
     */
    @Test
    void groupsTheDirectoryByWhatItSaysAboutItself() throws Exception {
        mockMvc.perform(get("/api/v1/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes.userDutyOrganizations[0].name").value("Example Agency"))
                .andExpect(jsonPath("$.attributes.userDutyOrganizations[0].count").value(2))
                .andExpect(jsonPath("$.attributes.userDutySubOrganizations.length()").value(2))
                .andExpect(jsonPath("$.attributes.userEmployeeTypes.length()").value(2))
                .andExpect(jsonPath("$.attributes.serverDutyOrganizations.length()").value(0));
    }

    @Test
    void needsSomebodySignedIn() throws Exception {
        mockMvc.perform(get("/api/v1/metrics").with(anonymous())).andExpect(status().isUnauthorized());
    }

    private CachedCertificate certificate(
            String subject,
            String keyAlgorithm,
            int keySize,
            String hashAlgorithm,
            String signatureAlgorithm,
            Instant notBefore,
            Instant notAfter,
            CertificateStatus status) {

        CachedCertificate certificate = new CachedCertificate(
                String.format("%064x", Math.abs((long) (subject + notAfter).hashCode())),
                "01",
                subject,
                "CN=Example CA",
                notBefore,
                notAfter,
                signatureAlgorithm,
                hashAlgorithm,
                keyAlgorithm,
                keySize,
                null,
                notBefore);
        certificate.updateStatus(status, notBefore);
        return certificate;
    }
}

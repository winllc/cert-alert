package com.winllc.certalert.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.CachedCertificateRepository;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
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
        holder.markSynced(now);
        holder.refreshCertificateSummary();
        userRepository.save(holder);

        DirectoryUser empty = new DirectoryUser("uid=empty,ou=people");
        empty.setUid("empty");
        empty.refreshIdentifiers("empty@example.gov");
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

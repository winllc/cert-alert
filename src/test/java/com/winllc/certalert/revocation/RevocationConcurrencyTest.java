package com.winllc.certalert.revocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.service.RevocationService;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestCa;
import com.winllc.certalert.support.TestOcspResponder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * How long the check takes when a responder is the only way to answer.
 *
 * <p>A revocation list is one download for a whole authority. A responder is one request
 * per certificate, and a request across a real network is not instant - so asked one after
 * another, a directory of any size does not finish overnight. The work is all waiting, so
 * it is done several at a time.
 *
 * <p>The responder here answers slowly on purpose and counts how many requests it was
 * holding at once, which is the part worth asserting: wall-clock timings belong to whatever
 * else the machine is doing.
 */
@SpringBootTest
@ActiveProfiles("test")
class RevocationConcurrencyTest {

    private static final int PEOPLE = 12;
    private static final long ANSWERS_IN_MILLIS = 200;

    private static EmbeddedDirectory directory;
    private static TestOcspResponder responder;
    private static Path issuerDirectory;

    @Autowired
    private RevocationService revocationService;

    @Autowired
    private DirectorySyncService syncService;

    @BeforeAll
    static void publishADirectory() throws IOException {
        TestCa ca = new TestCa("Example Slow CA");
        responder = new TestOcspResponder(ca);
        responder.takesThisLong(ANSWERS_IN_MILLIS);

        issuerDirectory = Files.createTempDirectory("cert-alert-slow-issuers");
        Files.writeString(issuerDirectory.resolve("ca.pem"), ca.certificatePem());

        Instant now = Instant.now();
        directory = new EmbeddedDirectory();
        for (int i = 0; i < PEOPLE; i++) {
            // No distribution point and no responder address: the configured default is the
            // only way to answer, which is the case that costs a request each.
            TestCa.Issued issued = ca.issue(
                    "slow" + i + "@example.gov",
                    now.minus(Duration.ofDays(10)),
                    now.plus(Duration.ofDays(355)),
                    null,
                    null);
            directory.addUser("slow" + i, "Slow Person" + i, "slow" + i + "@example.gov", issued.der());
        }
    }

    @AfterAll
    static void stop() {
        if (directory != null) {
            directory.close();
        }
        if (responder != null) {
            responder.close();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", () -> directory.url());
        registry.add("spring.ldap.base", () -> EmbeddedDirectory.BASE_DN);
        registry.add("cert-alert.ldap.user.search-base", () -> "ou=people");
        registry.add("cert-alert.ldap.server.search-base", () -> "ou=servers");
        registry.add("cert-alert.revocation.issuer-directory", () -> issuerDirectory.toString());
        registry.add("cert-alert.revocation.default-ocsp-url", responder::url);
        registry.add("cert-alert.revocation.workers", () -> "8");
    }

    @Test
    void theAuthoritiesAreAskedSeveralAtATime() {
        syncService.syncUsers();

        Instant started = Instant.now();
        RevocationService.Result result = revocationService.checkAll();
        Duration took = Duration.between(started, Instant.now());

        assertThat(result.checked()).isGreaterThanOrEqualTo(PEOPLE);
        assertThat(responder.mostAtOnce())
                .as("the responder was answering more than one request at a time")
                .isGreaterThan(1)
                // And no more than it was told: a width that is not a width is an
                // unannounced load test of somebody else's responder.
                .isLessThanOrEqualTo(8);
        // Generous, because a timing assertion is a promise about the machine as much as
        // the code: serially this could not come in under the whole queue of answers.
        assertThat(took)
                .as("not one after another")
                .isLessThan(Duration.ofMillis(ANSWERS_IN_MILLIS * PEOPLE));
    }
}

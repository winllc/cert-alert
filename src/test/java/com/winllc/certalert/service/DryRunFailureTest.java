package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.NotificationRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What a rehearsal says when the messages cannot be built at all.
 *
 * <p>Templates of a deployment's own are the ordinary way for that to happen: the wording
 * of these messages is theirs, so the file is theirs to edit, and an expression with a
 * typo in it throws where it is rendered. That used to leave a line in the log and nothing
 * on the page - "nothing would be sent", with a note blaming whichever unrelated thing came
 * next down the list. Surfacing exactly this is what a rehearsal is for.
 */
@SpringBootTest(
        properties = {
            "cert-alert.notifications.email.enabled=true",
            "cert-alert.notifications.email.from=cert-alert@example.gov"
        })
@ActiveProfiles("test")
class DryRunFailureTest {

    private static Path templates;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private DirectoryUserRepository users;

    @BeforeAll
    static void writeATemplateThatWillNotRender() throws IOException {
        templates = Files.createTempDirectory("cert-alert-broken-templates");
        Files.createDirectories(templates.resolve("email"));
        // Valid Thymeleaf syntax naming something that is not there, which is what a typo
        // in an overridden template looks like.
        Files.writeString(
                templates.resolve("email/expiring-user.txt"),
                "Hello [(${digest.recipientName})], [(${digest.thisDoesNotExist})]\n");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("cert-alert.notifications.email.template-directory", () -> templates.toString());
    }

    @BeforeEach
    void seed() {
        notifications.deleteAll();
        users.deleteAll();

        Instant now = Instant.now();
        DirectoryUser alice = new DirectoryUser("uid=alice,ou=people");
        alice.setUid("alice");
        alice.setDisplayName("Alice Archer");
        alice.refreshIdentifiers("alice@example.gov");
        CachedCertificate certificate = new CachedCertificate(
                "a".repeat(64), "01", "CN=alice@example.gov", "CN=Example CA",
                now.minus(Duration.ofDays(360)), now.plus(Duration.ofDays(5)),
                "SHA256withRSA", "SHA-256", "RSA", 2048, null, now);
        certificate.updateStatus(CertificateStatus.EXPIRING_SOON, now);
        alice.addCertificate(certificate);
        alice.markSynced(now);
        alice.refreshCertificateSummary();
        users.save(alice);
    }

    /** The rehearsal says what broke, who it was for, and does not blame anything else. */
    @Test
    void aTemplateThatWillNotRenderIsReportedRatherThanSwallowed() {
        NotificationService.DigestResult result = notificationService.digest(true);

        assertThat(result.messages()).isEmpty();
        assertThat(result.failures())
                .singleElement()
                .satisfies(failure -> {
                    assertThat(failure.to()).isEqualTo("alice@example.gov");
                    assertThat(failure.reason()).isNotBlank();
                });
        assertThat(result.note())
                .contains("could not be built")
                .contains("alice@example.gov")
                // Not "nothing here says why", and not the switched-off setting either.
                .doesNotContain("says why")
                .doesNotContain("switched off");
    }

    /** A real run reports it too, rather than quietly writing notifications and no email. */
    @Test
    void aRealRunSaysSoAsWell() {
        NotificationService.DigestResult result = notificationService.digest();

        assertThat(result.emailsSent()).isZero();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.note()).contains("could not be built");
        // The page still gets its round-up: what is expiring is true whatever the mailer did.
        assertThat(notifications.count()).isEqualTo(1);
    }

    /** Everything a real run needs except somewhere to send it. */
    @org.springframework.boot.test.context.TestConfiguration
    static class MailConfiguration {

        @org.springframework.context.annotation.Bean
        org.springframework.mail.javamail.JavaMailSender mailSender() {
            return new org.springframework.mail.javamail.JavaMailSenderImpl() {
                @Override
                protected void doSend(
                        jakarta.mail.internet.MimeMessage[] messages, Object[] originalMessages) {
                    // Never reached: these messages do not get as far as being built.
                }
            };
        }
    }
}

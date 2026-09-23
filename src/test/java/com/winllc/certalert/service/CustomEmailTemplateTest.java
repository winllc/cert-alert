package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.NotificationRepository;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Email templates read from a directory rather than out of the jar.
 *
 * <p>What these messages say is a deployment's own - who signs them, what the internal
 * renewal process is called, what somebody is meant to do next - and none of that belongs
 * in an image everybody shares. The alternative to this is rebuilding the image to change
 * a sentence.
 *
 * <p>The case that matters is the partial one. A directory holding one file overrides one
 * template; everything else still comes from the jar, so changing the wording of a
 * plain-text message does not mean taking ownership of all four and keeping them in step
 * with the packaged ones for ever.
 */
@SpringBootTest(
        properties = {
            "cert-alert.notifications.email.enabled=true",
            "cert-alert.notifications.email.from=cert-alert@example.gov"
        })
@ActiveProfiles("test")
class CustomEmailTemplateTest {

    private static final String OWN_WORDING = "Renew it through the Service Desk, reference PKI-RENEW.";

    private static Path templateDirectory;

    @BeforeAll
    static void writeTheOverride() throws IOException {
        // Only the plain-text half of one of the two messages, which is the point: the
        // HTML half and the server message have to keep coming from the jar.
        templateDirectory = Files.createTempDirectory("cert-alert-templates");
        Path email = Files.createDirectories(templateDirectory.resolve("email"));
        Files.writeString(email.resolve("expiring-user.txt"),
                "[(${greeting})]\n\n" + OWN_WORDING + "\n");
    }

    @DynamicPropertySource
    static void templates(DynamicPropertyRegistry registry) {
        registry.add("cert-alert.notifications.email.template-directory", () -> templateDirectory.toString());
    }

    @TestConfiguration
    static class MailConfiguration {

        @Bean
        CapturingMailSender mailSender() {
            return new CapturingMailSender();
        }
    }

    static class CapturingMailSender extends JavaMailSenderImpl {

        private final List<MimeMessage> sent = new ArrayList<>();

        @Override
        protected void doSend(MimeMessage[] messages, Object[] originalMessages) {
            for (MimeMessage message : messages) {
                try {
                    message.saveChanges();
                } catch (jakarta.mail.MessagingException e) {
                    throw new IllegalStateException(e);
                }
                sent.add(message);
            }
        }
    }

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private CapturingMailSender mailSender;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private DirectoryUserRepository users;

    @Autowired
    private DirectoryServerRepository servers;

    @BeforeEach
    void seed() {
        mailSender.sent.clear();
        notifications.deleteAll();
        servers.deleteAll();
        users.deleteAll();

        Instant now = Instant.now();
        DirectoryUser alice = new DirectoryUser("uid=alice,ou=people");
        alice.setUid("alice");
        alice.setDisplayName("Alice Archer");
        alice.refreshIdentifiers("alice@example.gov");
        alice.addCertificate(certificate(
                "CN=alice@example.gov", now.plus(Duration.ofHours(132)), CertificateStatus.EXPIRING_SOON, now));
        alice.markSynced(now);
        alice.refreshCertificateSummary();
        users.save(alice);
    }

    @Test
    void theTemplateInTheDirectoryIsUsedInPlaceOfThePackagedOne() {
        notificationService.digest();

        assertThat(mailSender.sent).hasSize(1);
        assertThat(text(mailSender.sent.getFirst())).contains(OWN_WORDING);
    }

    /**
     * And the halves it does not hold still come from the jar. Without this the feature is
     * all-or-nothing: overriding a sentence would mean owning every template.
     */
    @Test
    void andTheOnesItDoesNotHoldStillComeFromTheJar() {
        notificationService.digest();

        String html = html(mailSender.sent.getFirst());
        assertThat(html).isNotNull();
        assertThat(html).doesNotContain(OWN_WORDING);
        // The packaged HTML, which the directory says nothing about.
        assertThat(html).contains("<html").contains("Alice Archer");
    }

    private String html(MimeMessage message) {
        return part(message, "text/html");
    }

    private String text(MimeMessage message) {
        return part(message, "text/plain");
    }

    private String part(Part part, String contentType) {
        try {
            if (part.isMimeType(contentType)) {
                return part.getContent().toString();
            }
            if (part.getContent() instanceof MimeMultipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    String found = part(multipart.getBodyPart(i), contentType);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private CachedCertificate certificate(
            String subject, Instant notAfter, CertificateStatus status, Instant now) {
        CachedCertificate certificate = new CachedCertificate(
                String.format("%064x", Math.abs((long) subject.hashCode())), "01", subject, "CN=Example CA",
                now.minus(Duration.ofDays(365)), notAfter, "SHA256withRSA", "SHA-256", "RSA", 2048, null, now);
        certificate.updateStatus(status, now);
        return certificate;
    }
}

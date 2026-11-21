package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.repository.OutboundEmailRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;

/**
 * A rehearsal and a real round-up at once on one replica: each run keeps what it built and
 * queued to itself, so the rehearsal queues nothing and the real run renders nothing.
 */
@SpringBootTest(
        properties = {
            "cert-alert.notifications.email.enabled=true",
            "cert-alert.notifications.email.from=cert-alert@example.gov"
        })
@ActiveProfiles("test")
class NotificationMailerRunTest {

    @TestConfiguration
    static class MailConfiguration {

        @Bean
        JavaMailSenderImpl mailSender() {
            return new JavaMailSenderImpl();
        }
    }

    @Autowired
    private NotificationMailer mailer;

    @Autowired
    private OutboundEmailRepository outbox;

    private List<Long> queued = List.of();

    @AfterEach
    void clear() {
        outbox.deleteAllById(queued);
    }

    @Test
    void aRehearsalStartedMidRunDoesNotTakeOverTheRealOne() {
        Instant now = Instant.now();
        NotificationRecipients.Recipient alice =
                new NotificationRecipients.Recipient(null, "alice@example.gov", "Alice");
        NotificationRecipients.Recipient bob = new NotificationRecipients.Recipient(null, "bob@example.gov", "Bob");

        NotificationMailer.Run real = mailer.beginRun(false);
        mailer.send(real, alice, entries("alice", now));
        // The rehearsal begins while the real run is mid-loop.
        NotificationMailer.Run rehearsal = mailer.beginRun(true);
        mailer.send(rehearsal, alice, entries("alice", now));
        mailer.send(real, bob, entries("bob", now));
        mailer.send(rehearsal, bob, entries("bob", now));

        assertThat(real.isDryRun()).isFalse();
        assertThat(real.queued()).hasSize(2);
        assertThat(real.rendered()).isEmpty();
        assertThat(real.sent()).isEqualTo(2);

        assertThat(rehearsal.isDryRun()).isTrue();
        assertThat(rehearsal.queued()).isEmpty();
        assertThat(rehearsal.rendered()).extracting(NotificationMailer.Rendered::to)
                .containsExactly("alice@example.gov", "bob@example.gov");
        queued = real.queued();
        assertThat(outbox.findAllById(queued)).hasSize(2);
    }

    private static List<ExpiryDigest.Entry> entries(String name, Instant now) {
        CachedCertificate certificate = new CachedCertificate(name, "01", "CN=" + name, "CN=ca",
                now.minus(Duration.ofDays(300)), now.plus(Duration.ofDays(5)), "SHA256withRSA", "SHA-256", "RSA",
                2048, null, now);
        AuditEvent.SubjectRef subject = new AuditEvent.SubjectRef(OwnerType.USER, 1L, "uid=" + name, name);
        return Credential.group(List.of(certificate), Duration.ofDays(1)).stream()
                .map(credential -> ExpiryDigest.Entry.of(credential, subject, OwnerType.USER, now))
                .toList();
    }
}

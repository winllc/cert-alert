package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.winllc.certalert.alert.CertificateAlert;
import com.winllc.certalert.alert.EmailAlertNotifier;
import com.winllc.certalert.config.MailOutboxProperties;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.OutboundEmail;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.Severity;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.NotificationRepository;
import com.winllc.certalert.repository.OutboundEmailRepository;
import jakarta.mail.internet.MimeMessage;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Email is written down first and sent after: a mail server that is down loses nothing, and
 * one that is slow holds no database transaction open.
 */
@SpringBootTest(
        properties = {
            "cert-alert.notifications.email.enabled=true",
            "cert-alert.notifications.email.from=cert-alert@example.gov",
            "cert-alert.alerts.email.enabled=true",
            "cert-alert.alerts.email.to=pki-team@example.gov"
        })
@ActiveProfiles("test")
class MailOutboxTest {

    @TestConfiguration
    static class MailConfiguration {

        @Bean
        FlakyMailSender mailSender() {
            return new FlakyMailSender();
        }
    }

    /** A mail server that can be taken down, and that notices whether it is asked from inside a transaction. */
    static class FlakyMailSender extends JavaMailSenderImpl {

        private final List<MimeMessage> sent = new ArrayList<>();
        private volatile boolean down;
        private volatile boolean askedInsideATransaction;

        @Override
        protected void doSend(MimeMessage[] messages, Object[] originalMessages) {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                askedInsideATransaction = true;
            }
            if (down) {
                throw new MailSendException("Connection refused: smtp.example.gov:587");
            }
            sent.addAll(List.of(messages));
        }
    }

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private MailOutbox outbox;

    @Autowired
    private MailOutboxProperties outboxProperties;

    @Autowired
    private OutboundEmailRepository outboundEmails;

    @Autowired
    private FlakyMailSender mailSender;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private DirectoryUserRepository users;

    @Autowired
    private DirectoryServerRepository servers;

    @Autowired
    private EmailAlertNotifier alerts;

    @Autowired
    private PlatformTransactionManager transactions;

    @BeforeEach
    void seed() {
        mailSender.sent.clear();
        mailSender.down = false;
        mailSender.askedInsideATransaction = false;
        outboundEmails.deleteAll();
        notifications.deleteAll();
        servers.deleteAll();
        users.deleteAll();

        Instant now = Instant.now();
        DirectoryUser alice = new DirectoryUser("uid=alice,ou=people");
        alice.setUid("alice");
        alice.setDisplayName("Alice Archer");
        alice.refreshIdentifiers("alice@example.gov");
        CachedCertificate certificate = new CachedCertificate(
                "a".repeat(64), "01", "CN=alice@example.gov", "CN=Example CA",
                now.minus(Duration.ofDays(365)), now.plus(Duration.ofDays(5)),
                "SHA256withRSA", "SHA-256", "RSA", 2048, null, now);
        certificate.updateStatus(CertificateStatus.EXPIRING_SOON, now);
        alice.addCertificate(certificate);
        alice.markSynced(now);
        alice.refreshCertificateSummary();
        users.save(alice);
    }

    @AfterEach
    void restore() {
        outboxProperties.setGiveUpAfter(Duration.ofDays(3));
    }

    @Test
    void theRoundUpIsSentAfterItsTransactionNotInsideIt() {
        NotificationService.DigestResult result = notificationService.digest();

        assertThat(result.emailsSent()).isEqualTo(1);
        assertThat(mailSender.sent).hasSize(1);
        assertThat(mailSender.askedInsideATransaction).isFalse();
        assertThat(outboundEmails.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getState()).isEqualTo(OutboundEmail.State.SENT));
        assertThat(notifications.findAll()).singleElement()
                .satisfies(notification -> assertThat(notification.getEmailedAt()).isNotNull());
    }

    @Test
    void aMailServerThatIsDownLosesNothing() {
        mailSender.down = true;

        NotificationService.DigestResult result = notificationService.digest();

        // Said, rather than reported as sent or as nothing to do.
        assertThat(result.emailsSent()).isZero();
        assertThat(result.note()).contains("queued to try again").contains("Connection refused");
        // The page has its round-up, but nothing says it was emailed.
        assertThat(notifications.findAll()).singleElement()
                .satisfies(notification -> assertThat(notification.getEmailedAt()).isNull());
        OutboundEmail waiting = outboundEmails.findAll().getFirst();
        assertThat(waiting.getState()).isEqualTo(OutboundEmail.State.PENDING);
        assertThat(waiting.getAttempts()).isEqualTo(1);
        assertThat(waiting.getLastError()).contains("Connection refused");
        assertThat(waiting.getNextAttemptAt())
                .isCloseTo(Instant.now().plus(Duration.ofMinutes(1)), within(30, ChronoUnit.SECONDS));

        // Not due yet, so a pass now leaves it alone.
        mailSender.down = false;
        assertThat(outbox.deliverDue()).isZero();
        assertThat(mailSender.sent).isEmpty();

        // And once it is tried again it goes, and the notification says so.
        assertThat(outbox.deliver(List.of(waiting.getId())).sent()).isEqualTo(1);
        assertThat(mailSender.sent).hasSize(1);
        assertThat(outboundEmails.findById(waiting.getId()).orElseThrow().getState())
                .isEqualTo(OutboundEmail.State.SENT);
        assertThat(notifications.findAll()).singleElement()
                .satisfies(notification -> assertThat(notification.getEmailedAt()).isNotNull());
    }

    @Test
    void aNewerRoundUpReplacesOneStillWaiting() {
        mailSender.down = true;
        notificationService.digest();
        notificationService.digest();

        assertThat(outboundEmails.findAll())
                .extracting(OutboundEmail::getState)
                .containsExactlyInAnyOrder(OutboundEmail.State.SUPERSEDED, OutboundEmail.State.PENDING);

        // So a mail server back after an outage delivers one round-up, not one per day missed.
        mailSender.down = false;
        Long pending = outboundEmails.findAll().stream()
                .filter(row -> row.getState() == OutboundEmail.State.PENDING)
                .findFirst()
                .orElseThrow()
                .getId();
        outbox.deliver(List.of(pending));
        assertThat(mailSender.sent).hasSize(1);
    }

    @Test
    void aMessageTooOldToMatterIsGivenUpOn() {
        mailSender.down = true;
        notificationService.digest();
        Long id = outboundEmails.findAll().getFirst().getId();

        outboxProperties.setGiveUpAfter(Duration.ZERO);
        outbox.deliver(List.of(id));

        OutboundEmail row = outboundEmails.findById(id).orElseThrow();
        assertThat(row.getState()).isEqualTo(OutboundEmail.State.FAILED);
        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(outbox.failed()).isEqualTo(1);
    }

    @Test
    void anAlertIsQueuedWithTheSweepThatRaisedItAndSentAfter() {
        CertificateAlert alert = new CertificateAlert(
                OwnerType.SERVER, 7L, "web01", "cn=web01,ou=servers", "ops@example.gov",
                CertificateStatus.EXPIRING_SOON, Severity.CRITICAL, "CN=web01.example.gov", "b".repeat(64),
                "02", Instant.now().plus(Duration.ofDays(3)), 3L, Instant.now());

        new TransactionTemplate(transactions).executeWithoutResult(status -> alerts.send(alert));
        assertThat(mailSender.sent).as("nothing is sent from inside the sweep").isEmpty();
        assertThat(outbox.pending()).isEqualTo(1);

        assertThat(outbox.deliverDue()).isEqualTo(1);
        assertThat(mailSender.sent).hasSize(1);
        assertThat(mailSender.askedInsideATransaction).isFalse();
    }

    @Test
    void retriesBackOffUpToACeiling() {
        Duration initial = Duration.ofMinutes(1);
        Duration max = Duration.ofHours(1);

        assertThat(MailOutbox.backoff(1, initial, max)).isEqualTo(Duration.ofMinutes(1));
        assertThat(MailOutbox.backoff(2, initial, max)).isEqualTo(Duration.ofMinutes(2));
        assertThat(MailOutbox.backoff(4, initial, max)).isEqualTo(Duration.ofMinutes(8));
        assertThat(MailOutbox.backoff(7, initial, max)).isEqualTo(Duration.ofHours(1));
        assertThat(MailOutbox.backoff(40, initial, max)).isEqualTo(Duration.ofHours(1));
    }
}

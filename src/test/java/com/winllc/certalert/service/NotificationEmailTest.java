package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.NotificationRepository;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;

/**
 * The round-up as it actually arrives: rendered from the templates and handed to a mail
 * sender.
 *
 * <p>Two templates, because they are two different messages. What somebody has to renew
 * themselves is not the same ask as what they have to chase on a server, and the wording,
 * the subject line and the heading of the first column all differ - so a person who is both
 * gets one of each rather than a single list that mixes them.
 */
@SpringBootTest(
        properties = {
            "cert-alert.notifications.email.enabled=true",
            "cert-alert.notifications.email.from=cert-alert@example.gov"
        })
@ActiveProfiles("test")
class NotificationEmailTest {

    @TestConfiguration
    static class MailConfiguration {

        @Bean
        CapturingMailSender mailSender() {
            return new CapturingMailSender();
        }
    }

    /** Everything a real sender does up to the point of opening a socket. */
    static class CapturingMailSender extends JavaMailSenderImpl {

        private final List<MimeMessage> sent = new ArrayList<>();

        @Override
        protected void doSend(MimeMessage[] messages, Object[] originalMessages) {
            for (MimeMessage message : messages) {
                try {
                    // What a transport does before it writes the message out; without it the
                    // parts carry no content type and read back as one lump.
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

        // A server Alice is the published point of contact for, and one nobody has claimed.
        servers.save(server("cn=web01,ou=servers", "web01", "alice@example.gov",
                certificate("CN=web01.example.gov", now.minus(Duration.ofHours(84)), CertificateStatus.EXPIRED, now)));
        servers.save(server("cn=db01,ou=servers", "db01", "ops@example.gov",
                certificate("CN=db01.example.gov", now.plus(Duration.ofHours(228)),
                        CertificateStatus.EXPIRING_SOON, now)));
    }

    /**
     * Alice is both a certificate holder and a server's contact, so she gets both messages,
     * each from its own template and each saying what it is about.
     */
    @Test
    void aPersonWhoIsBothGetsOneMessageOfEachKind() {
        notificationService.digest();

        List<MimeMessage> hers = to("alice@example.gov");
        assertThat(hers).hasSize(2);

        String ownSubject = subject(hers.getFirst());
        String serverSubject = subject(hers.get(1));
        assertThat(ownSubject).isEqualTo("[cert-alert] 1 of your credential(s) expiring soon");
        assertThat(serverSubject).isEqualTo("[cert-alert] 1 server certificate(s) expired");

        String own = html(hers.getFirst());
        assertThat(own)
                .contains("Your certificates")
                .contains("Hello Alice Archer,")
                .contains("Issued to")
                .contains("CN=alice@example.gov")
                .contains("in 5 day(s)")
                .contains("these certificates were issued to you")
                // Her own message says nothing about the server she looks after.
                .doesNotContain("CN=web01.example.gov");

        String server = html(hers.get(1));
        assertThat(server)
                .contains("Servers you are a contact for")
                .contains("point of contact for a server whose certificate needs attention")
                .contains("CN=web01.example.gov")
                .contains("3 day(s) ago")
                .doesNotContain("CN=alice@example.gov");
    }

    /** Both halves of every message, so a client that will not render HTML still reads. */
    @Test
    void everyMessageCarriesAPlainTextAlternative() {
        notificationService.digest();

        MimeMessage message = to("alice@example.gov").getFirst();
        String text = text(message);

        assertThat(text)
                .contains("Hello Alice Archer,")
                .contains("1 of your credential(s) expiring soon")
                .contains("CN=alice@example.gov")
                .contains("Expires in 5 day(s), on")
                .contains("RSA 2048 / SHA-256")
                .doesNotContain("<")
                .doesNotContain("&#");
    }

    /** An address nobody has claimed is written to as an address, without a name to use. */
    @Test
    void anUnclaimedAddressIsGreetedWithoutAName() {
        notificationService.digest();

        List<MimeMessage> theirs = to("ops@example.gov");
        assertThat(theirs).hasSize(1);
        assertThat(subject(theirs.getFirst())).isEqualTo("[cert-alert] 1 server certificate(s) expiring soon");
        assertThat(text(theirs.getFirst())).startsWith("Hello,");
        assertThat(html(theirs.getFirst())).contains("Hello,").contains("CN=db01.example.gov");
    }

    /** Nothing in either message is fetched when it is opened; an air-gap has to be enough. */
    @Test
    void nothingIsLoadedFromAnywhereElse() {
        notificationService.digest();

        for (MimeMessage message : mailSender.sent) {
            assertThat(html(message))
                    .doesNotContain("http://")
                    .doesNotContain("https://")
                    .doesNotContain("<img")
                    .doesNotContain("<link");
        }
    }

    /** What was emailed is recorded against the notification, which is what stops repeats. */
    @Test
    void theRoundUpRecordsThatItWentOut() {
        notificationService.digest();

        assertThat(notifications.findAll())
                .isNotEmpty()
                .allSatisfy(notification -> assertThat(notification.getEmailedAt()).isNotNull());
    }

    /**
     * A rehearsal: every message built and none of them sent, nothing written down either.
     * The question it answers - how many people would hear from this, and what would it say
     * - is one to settle before a mail server is pointed at a directory, not after.
     */
    @Test
    void aDryRunBuildsEveryMessageAndSendsNone() {
        NotificationService.DigestResult result = notificationService.digest(true);

        assertThat(result.dryRun()).isTrue();
        assertThat(result.emailEnabled()).isTrue();
        assertThat(mailSender.sent).as("nothing left the building").isEmpty();
        assertThat(notifications.count()).as("and nothing was written down").isZero();

        // Three messages to two people: Alice's own and Alice's server, plus the unclaimed
        // address's server.
        assertThat(result.peopleTold()).isEqualTo(2);
        assertThat(result.emailsSent()).isEqualTo(3);
        assertThat(result.messages()).hasSize(3);
        assertThat(result.messages())
                .extracting(NotificationMailer.Rendered::to)
                .containsExactlyInAnyOrder("alice@example.gov", "alice@example.gov", "ops@example.gov");

        NotificationMailer.Rendered own = result.messages().stream()
                .filter(message -> message.subject().contains("your credential"))
                .findFirst()
                .orElseThrow();
        assertThat(own.subject()).isEqualTo("[cert-alert] 1 of your credential(s) expiring soon");
        assertThat(own.html()).contains("Hello Alice Archer,").contains("CN=alice@example.gov");
        assertThat(own.text()).contains("CN=alice@example.gov").doesNotContain("<");
    }

    /** And a real run after one behaves as though the rehearsal never happened. */
    @Test
    void aRealRunAfterARehearsalStillSends() {
        notificationService.digest(true);

        NotificationService.DigestResult real = notificationService.digest();

        assertThat(real.dryRun()).isFalse();
        assertThat(real.messages()).isEmpty();
        assertThat(mailSender.sent).hasSize(3);
        assertThat(real.emailsSent()).isEqualTo(3);
        assertThat(notifications.count()).as("one notification per person").isEqualTo(2);
    }

    private List<MimeMessage> to(String address) {
        return mailSender.sent.stream()
                .filter(message -> {
                    try {
                        return List.of(message.getAllRecipients()).stream()
                                .anyMatch(recipient -> recipient.toString().equals(address));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
    }

    private String subject(MimeMessage message) {
        try {
            return message.getSubject();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String html(MimeMessage message) {
        return part(message, "text/html");
    }

    private String text(MimeMessage message) {
        return part(message, "text/plain");
    }

    /** Walks the alternative down to the one part asked for. */
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

    private DirectoryServer server(String dn, String commonName, String poc, CachedCertificate certificate) {
        DirectoryServer server = new DirectoryServer(dn);
        server.setCommonName(commonName);
        server.setServerPocs(List.of(poc));
        server.addCertificate(certificate);
        server.markSynced(Instant.now());
        server.refreshCertificateSummary();
        return server;
    }

    private CachedCertificate certificate(
            String subjectDn, Instant notAfter, CertificateStatus status, Instant now) {

        CachedCertificate certificate = new CachedCertificate(
                String.format("%064x", Math.abs((long) subjectDn.hashCode())),
                "01",
                subjectDn,
                "CN=Example CA",
                now.minus(Duration.ofDays(365)),
                notAfter,
                "SHA256withRSA",
                "SHA-256",
                "RSA",
                2048,
                null,
                now);
        certificate.updateStatus(status, now);
        return certificate;
    }
}

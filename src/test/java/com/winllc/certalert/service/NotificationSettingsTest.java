package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.Notification;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.NotificationRepository;
import com.winllc.certalert.repository.NotificationSettingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/**
 * How many days before expiry the round-up writes to people, once that is somebody's
 * decision rather than the configuration file's.
 *
 * <p>The substance is the last test: the setting has to reach past the window a certificate
 * is marked EXPIRING_SOON in, or setting it to sixty days would quietly go on reporting
 * thirty.
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationSettingsTest {

    @Autowired
    private NotificationSettingsService settingsService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationSettingRepository settings;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private DirectoryUserRepository users;

    private Long userId;

    @BeforeEach
    void reset() {
        settings.deleteAll();
        notifications.deleteAll();
        users.deleteAll();

        Instant now = Instant.now();
        DirectoryUser holder = new DirectoryUser("uid=holder,ou=people");
        holder.setUid("holder");
        holder.setDisplayName("Cert Holder");
        holder.refreshIdentifiers("holder@example.gov");
        // Inside the warning window, and well outside it. The second is the interesting one:
        // it is VALID, so nothing about its cached state says it belongs in a round-up.
        holder.addCertificate(certificate("CN=soon", now.plus(Duration.ofDays(5)), CertificateStatus.EXPIRING_SOON));
        holder.addCertificate(certificate("CN=later", now.plus(Duration.ofDays(45)), CertificateStatus.VALID));
        holder.markSynced(now);
        holder.refreshCertificateSummary();
        userId = users.save(holder).getId();
    }

    /** The row is the deployment's, not a test's; leave the database as it was found. */
    @AfterEach
    void clear() {
        settings.deleteAll();
    }

    @Test
    void startsAtWhateverTheConfigurationSays() {
        NotificationSettingsService.Settings current = settingsService.current();

        assertThat(current.leadDays()).isEqualTo(30);
        assertThat(current.configuredLeadDays()).isEqualTo(30);
        assertThat(current.fromConfiguration()).as("nobody has set it here").isTrue();
        assertThat(current.updatedAt()).isNull();
        assertThat(current.updatedBy()).isNull();
    }

    @Test
    void settingItRecordsWhoAndWhen() {
        NotificationSettingsService.Settings saved = settingsService.update(45, "alice");

        assertThat(saved.leadDays()).isEqualTo(45);
        assertThat(saved.fromConfiguration()).isFalse();
        assertThat(saved.updatedBy()).isEqualTo("alice");
        assertThat(saved.updatedAt()).isNotNull();
        // And it is the deployment's answer, read back by anything that asks.
        assertThat(settingsService.leadDays()).isEqualTo(45);
        assertThat(settings.count()).as("one row, however often it is set").isEqualTo(1);

        settingsService.update(14, "bob");

        assertThat(settingsService.current().leadDays()).isEqualTo(14);
        assertThat(settingsService.current().updatedBy()).isEqualTo("bob");
        assertThat(settings.count()).isEqualTo(1);
    }

    @Test
    void refusesSomethingThatIsNotAWarning() {
        assertThatThrownBy(() -> settingsService.update(0, "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 365");
        assertThatThrownBy(() -> settingsService.update(366, "alice"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(settingsService.current().fromConfiguration()).isTrue();
    }

    /**
     * What the setting is for. A certificate 45 days out is VALID and outside the default
     * window; raising the setting has to reach it, and lowering it has to stop reaching what
     * it did.
     */
    @Test
    void theRoundUpLooksAsFarAheadAsItIsTold() {
        notificationService.digest();
        assertThat(messagesFor(userId)).singleElement().satisfies(message ->
                assertThat(message).contains("1 certificate(s) expiring").contains("CN=soon"));

        notifications.deleteAll();
        settingsService.update(60, "alice");
        notificationService.digest();

        assertThat(messagesFor(userId)).singleElement().satisfies(message ->
                assertThat(message).contains("2 certificate(s) expiring").contains("across 2"));

        notifications.deleteAll();
        settingsService.update(2, "alice");
        notificationService.digest();

        assertThat(messagesFor(userId)).as("nothing falls due inside two days").isEmpty();
    }

    private List<String> messagesFor(Long id) {
        return notificationService.forRecipient(id, PageRequest.of(0, 10)).getContent().stream()
                .map(Notification::getMessage)
                .toList();
    }

    private CachedCertificate certificate(String subjectDn, Instant notAfter, CertificateStatus status) {
        Instant now = Instant.now();
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

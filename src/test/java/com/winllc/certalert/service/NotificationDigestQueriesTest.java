package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.NotificationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Who to tell about an entry is a query or several (for a server, its contacts, published
 * points of contact and project administrators), so the round-up asks it once per entry
 * rather than once per credential the entry holds.
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationDigestQueriesTest {

    @MockitoSpyBean
    private NotificationRecipients recipients;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private DirectoryServerRepository servers;

    @Autowired
    private DirectoryUserRepository users;

    @BeforeEach
    void clear() {
        notifications.deleteAll();
        servers.deleteAll();
        users.deleteAll();
    }

    @Test
    void anEntrysRecipientsAreResolvedOncePerRun() {
        Instant now = Instant.now();
        DirectoryUser person = new DirectoryUser("uid=several,ou=people");
        person.setUid("several");
        person.refreshIdentifiers("several@example.gov");
        // Three names, so three credentials of one entry.
        for (String name : List.of("a", "b", "c")) {
            person.addCertificate(certificate("CN=" + name + ".several", now.plus(Duration.ofDays(5)), now));
        }
        person.markSynced(now);
        person.refreshCertificateSummary();
        users.save(person);
        clearInvocations(recipients);

        NotificationService.DigestResult result = notificationService.digest(true);

        assertThat(result.certificates()).isEqualTo(3);
        verify(recipients, times(1)).forUser(anyLong());
    }

    private static CachedCertificate certificate(String subjectDn, Instant notAfter, Instant now) {
        CachedCertificate certificate = new CachedCertificate(
                String.format("%064x", Math.abs((long) subjectDn.hashCode())), "01", subjectDn, "CN=Example CA",
                now.minus(Duration.ofDays(365)), notAfter, "SHA256withRSA", "SHA-256", "RSA", 2048, null, now);
        certificate.updateStatus(CertificateStatus.EXPIRING_SOON, now);
        return certificate;
    }
}

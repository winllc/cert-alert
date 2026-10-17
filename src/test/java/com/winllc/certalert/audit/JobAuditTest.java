package com.winllc.certalert.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.enssolutions.license.LicenseManager;
import com.winllc.certalert.domain.SyncJob;
import com.winllc.certalert.scheduling.DirectoryJobScheduler;
import com.winllc.certalert.service.CertificateRefreshService;
import com.winllc.certalert.service.DirectorySyncResult;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.service.NotificationService;
import com.winllc.certalert.service.RevocationService;
import com.winllc.certalert.support.TestLicenses;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Scheduled jobs: one {@code job.completed} or {@code job.failed} per run, with counts and
 * duration, and {@code job.skipped} once per stand-down for the license rather than per
 * firing. The same context as LicensedJobsTest, so it is shared rather than started again.
 */
@SpringBootTest(properties = {
    "cert-alert.ldap.sync.enabled=true",
    "cert-alert.ldap.sync.users-cron=-",
    "cert-alert.ldap.sync.servers-cron=-",
    "cert-alert.ldap.sync.refresh-cron=-",
    "cert-alert.ldap.certificate-cleanup.cron=-",
    "cert-alert.ldap.prune.cron=-",
    "cert-alert.audit.retention.cron=-",
    "cert-alert.revocation.cron=-",
    "cert-alert.notifications.digest.cron=-",
    "cert-alert.notifications.digest.enabled=true"
})
@ActiveProfiles("test")
class JobAuditTest {

    @Autowired
    private DirectoryJobScheduler scheduler;

    @Autowired
    private LicenseManager licenses;

    @Autowired
    private AuditStore store;

    @MockitoBean
    private DirectorySyncService syncService;

    @MockitoBean
    private CertificateRefreshService refreshService;

    @MockitoBean
    private RevocationService revocationService;

    @MockitoBean
    private NotificationService notificationService;

    @AfterEach
    void restore() {
        TestLicenses.restoreFull(licenses);
    }

    @Test
    void eachRunIsOneEventWithItsCountsAndDuration() {
        when(syncService.syncUsers()).thenReturn(
                new DirectorySyncResult(SyncJob.USERS, 1200, 3, 40, 2, 5, 0, 0, Duration.ofSeconds(9)));
        when(syncService.syncServers()).thenThrow(new IllegalStateException("directory unreachable"));
        long head = store.headSeq();

        scheduler.syncUsers();
        scheduler.syncServers();

        List<StoredEvent> events = store.after(head, 10);
        assertThat(events).extracting(StoredEvent::action).containsExactly("job.completed", "job.failed");
        assertThat(events.get(0).line()).contains(
                "\"category\":\"system\"", "\"actor\":{\"type\":\"system\",\"id\":\"scheduler\"",
                "\"job\":\"user sweep\"", "\"trigger\":\"schedule\"", "\"durationMs\":",
                "\"entriesSeen\":1200", "\"certificatesCached\":40");
        assertThat(events.get(1).line()).contains(
                "\"outcome\":\"failure\"", "\"severity\":\"warning\"", "\"job\":\"server sweep\"",
                "\"reason\":\"directory unreachable\"");
    }

    @Test
    void aJobPausedByTheLicenseIsRecordedOncePerStandDown() {
        scheduler.syncUsers();
        TestLicenses.uninstall(licenses);
        long head = store.headSeq();

        scheduler.syncUsers();
        scheduler.syncUsers();
        scheduler.syncUsers();

        List<StoredEvent> skipped = store.after(head, 20).stream()
                .filter(e -> "job.skipped".equals(e.action())).toList();
        assertThat(skipped).hasSize(1);
        assertThat(skipped.getFirst().line()).contains("\"job\":\"user sweep\"", "\"mode\":\"UNLICENSED\"");
    }
}

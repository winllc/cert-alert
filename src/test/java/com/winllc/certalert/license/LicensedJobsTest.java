package com.winllc.certalert.license;

import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.enssolutions.license.LicenseManager;
import com.winllc.certalert.scheduling.DirectoryJobScheduler;
import com.winllc.certalert.service.CertificateRefreshService;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.service.NotificationService;
import com.winllc.certalert.service.RevocationService;
import com.winllc.certalert.support.TestLicenses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Scheduled jobs run only while the license is in full effect and includes their feature,
 * and pause - without a restart either way - when it is not.
 */
@SpringBootTest(properties = {
    // The scheduler exists, but nothing fires on its own: these tests fire it.
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
class LicensedJobsTest {

    @Autowired
    private DirectoryJobScheduler scheduler;

    @Autowired
    private LicenseManager licenses;

    @MockitoBean
    private DirectorySyncService syncService;

    @MockitoBean
    private CertificateRefreshService refreshService;

    @MockitoBean
    private RevocationService revocationService;

    @MockitoBean
    private NotificationService notificationService;

    @BeforeEach
    void clear() {
        clearInvocations(syncService, refreshService, revocationService, notificationService);
    }

    @AfterEach
    void restore() {
        TestLicenses.restoreFull(licenses);
    }

    @Test
    void everyJobRunsUnderAFullLicense() {
        fireAll();

        verify(syncService).syncUsers();
        verify(syncService).syncServers();
        verify(refreshService).refresh();
        verify(revocationService).checkAll();
        verify(notificationService).digest();
        verify(notificationService).trim();
    }

    @Test
    void aJobWhoseFeatureIsNotIncludedStandsDown() {
        TestLicenses.install(licenses, TestLicenses.license().without("revocation-checks", "notifications").sign());

        fireAll();

        verify(revocationService, never()).checkAll();
        verify(notificationService, never()).digest();
        // The rest carry on, the in-app housekeeping included.
        verify(syncService).syncUsers();
        verify(notificationService).trim();
    }

    @Test
    void everythingPausesWhileReadOnly() {
        TestLicenses.install(licenses, TestLicenses.license().expired().sign());

        fireAll();

        verifyNothingRan();
    }

    @Test
    void everythingPausesWhileUnlicensed() {
        TestLicenses.uninstall(licenses);

        fireAll();

        verifyNothingRan();
    }

    @Test
    void installingALicenseResumesThemWithoutARestart() {
        TestLicenses.uninstall(licenses);
        scheduler.syncUsers();
        verify(syncService, never()).syncUsers();

        TestLicenses.restoreFull(licenses);
        scheduler.syncUsers();
        verify(syncService).syncUsers();
    }

    private void fireAll() {
        scheduler.syncUsers();
        scheduler.syncServers();
        scheduler.refreshExpiry();
        scheduler.checkRevocation();
        scheduler.expiryDigest();
    }

    private void verifyNothingRan() {
        verify(syncService, never()).syncUsers();
        verify(syncService, never()).syncServers();
        verify(refreshService, never()).refresh();
        verify(revocationService, never()).checkAll();
        verify(notificationService, never()).digest();
        verify(notificationService, never()).trim();
    }
}

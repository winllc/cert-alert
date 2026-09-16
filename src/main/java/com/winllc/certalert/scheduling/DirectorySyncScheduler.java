package com.winllc.certalert.scheduling;

import com.winllc.certalert.service.DirectorySyncService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the directory sync on a schedule. Disable with
 * {@code cert-alert.ldap.sync-enabled=false} to drive syncs through the API instead, for
 * example when an external scheduler owns the cadence.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "cert-alert.ldap", name = "sync-enabled", matchIfMissing = true)
public class DirectorySyncScheduler {

    private final DirectorySyncService syncService;

    public DirectorySyncScheduler(DirectorySyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(cron = "${cert-alert.ldap.sync-cron}", zone = "UTC")
    public void scheduledSync() {
        syncService.sync();
    }
}

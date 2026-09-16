package com.winllc.certalert.scheduling;

import com.winllc.certalert.service.CertificateSweepService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the periodic sweep. Disable with {@code cert-alert.scan-enabled=false} to drive
 * checks purely through the API, for example when an external scheduler owns the cadence.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "cert-alert", name = "scan-enabled", matchIfMissing = true)
public class CertificateScanScheduler {

    private final CertificateSweepService sweepService;

    public CertificateScanScheduler(CertificateSweepService sweepService) {
        this.sweepService = sweepService;
    }

    @Scheduled(cron = "${cert-alert.scan-cron}", zone = "UTC")
    public void scheduledSweep() {
        sweepService.sweep();
    }
}

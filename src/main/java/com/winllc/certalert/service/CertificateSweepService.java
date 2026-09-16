package com.winllc.certalert.service;

import com.winllc.certalert.domain.CertificateCheck;
import com.winllc.certalert.domain.CertificateTarget;
import com.winllc.certalert.repository.CertificateTargetRepository;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Sweeps every enabled target. Each target is checked in its own transaction so that one
 * slow or failing endpoint cannot roll back or stall the rest of the sweep.
 */
@Service
public class CertificateSweepService {

    private static final Logger log = LoggerFactory.getLogger(CertificateSweepService.class);

    private final CertificateTargetRepository targetRepository;
    private final CertificateMonitorService monitorService;

    public CertificateSweepService(
            CertificateTargetRepository targetRepository, CertificateMonitorService monitorService) {
        this.targetRepository = targetRepository;
        this.monitorService = monitorService;
    }

    public SweepResult sweep() {
        long startedAt = System.nanoTime();
        List<Long> targetIds = enabledTargetIds();
        log.info("Starting certificate sweep over {} enabled target(s)", targetIds.size());

        int unhealthy = 0;
        int errored = 0;
        for (Long targetId : targetIds) {
            try {
                CertificateCheck check = monitorService.checkTarget(targetId);
                if (check.getStatus().isAlertable()) {
                    unhealthy++;
                }
            } catch (RuntimeException e) {
                errored++;
                log.error("Check failed unexpectedly for target id {}", targetId, e);
            }
        }

        SweepResult result =
                new SweepResult(targetIds.size(), unhealthy, errored, Duration.ofNanos(System.nanoTime() - startedAt));
        log.info("Certificate sweep finished: {} checked, {} unhealthy, {} errored, took {} ms",
                result.checked(), result.unhealthy(), result.errored(), result.duration().toMillis());
        return result;
    }

    /**
     * Ids only: the sweep deliberately does not hold entities across checks, so each
     * {@link CertificateMonitorService#checkTarget(Long)} call works on a fresh, managed instance.
     */
    private List<Long> enabledTargetIds() {
        return targetRepository.findByEnabledTrue().stream()
                .map(CertificateTarget::getId)
                .toList();
    }
}

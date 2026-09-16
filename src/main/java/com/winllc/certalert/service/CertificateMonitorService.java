package com.winllc.certalert.service;

import com.winllc.certalert.alert.AlertDispatcher;
import com.winllc.certalert.alert.CertificateAlert;
import com.winllc.certalert.config.CertAlertProperties;
import com.winllc.certalert.domain.CertificateCheck;
import com.winllc.certalert.domain.CertificateTarget;
import com.winllc.certalert.domain.CheckStatus;
import com.winllc.certalert.domain.Severity;
import com.winllc.certalert.repository.CertificateCheckRepository;
import com.winllc.certalert.repository.CertificateTargetRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checks a single target: inspects its certificate, stores the result, and decides
 * whether the outcome is worth alerting on.
 */
@Service
public class CertificateMonitorService {

    private static final Logger log = LoggerFactory.getLogger(CertificateMonitorService.class);

    private final CertificateTargetRepository targetRepository;
    private final CertificateCheckRepository checkRepository;
    private final CertificateInspector inspector;
    private final CertificateStatusEvaluator evaluator;
    private final AlertDispatcher alertDispatcher;
    private final CertAlertProperties properties;
    private final Clock clock;

    public CertificateMonitorService(
            CertificateTargetRepository targetRepository,
            CertificateCheckRepository checkRepository,
            CertificateInspector inspector,
            CertificateStatusEvaluator evaluator,
            AlertDispatcher alertDispatcher,
            CertAlertProperties properties,
            Clock clock) {
        this.targetRepository = targetRepository;
        this.checkRepository = checkRepository;
        this.inspector = inspector;
        this.evaluator = evaluator;
        this.alertDispatcher = alertDispatcher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Checks the given target and records the outcome.
     *
     * @throws ResourceNotFoundException if no such target exists
     */
    @Transactional
    public CertificateCheck checkTarget(Long targetId) {
        CertificateTarget target = targetRepository
                .findById(targetId)
                .orElseThrow(() -> ResourceNotFoundException.target(targetId));
        return check(target);
    }

    @Transactional
    public CertificateCheck check(CertificateTarget target) {
        Instant now = Instant.now(clock);
        CertificateCheck check = performCheck(target, now);

        checkRepository.save(check);
        target.applyCheckResult(check);
        maybeAlert(target, check, now);
        targetRepository.save(target);

        return check;
    }

    private CertificateCheck performCheck(CertificateTarget target, Instant now) {
        try {
            CertificateDetails details = inspector.inspect(target.getHostname(), target.getPort());
            CheckStatus status = evaluator.evaluate(details.notAfter(), now);
            long daysUntilExpiry = evaluator.daysUntilExpiry(details.notAfter(), now);
            log.debug("Target '{}' is {} ({} day(s) until expiry)", target.getName(), status, daysUntilExpiry);
            return CertificateCheck.success(
                    target,
                    status,
                    now,
                    details.subject(),
                    details.issuer(),
                    details.serialNumber(),
                    details.notBefore(),
                    details.notAfter(),
                    daysUntilExpiry);
        } catch (CertificateInspectionException e) {
            log.warn("Target '{}' could not be checked: {}", target.getName(), e.getMessage());
            return CertificateCheck.failure(target, now, e.getMessage());
        }
    }

    private void maybeAlert(CertificateTarget target, CertificateCheck check, Instant now) {
        CheckStatus status = check.getStatus();
        if (!status.isAlertable()) {
            if (target.getLastAlertedStatus() != null) {
                alertDispatcher.dispatch(CertificateAlert.from(target, check, Severity.INFO));
                target.clearAlertState();
            }
            return;
        }
        if (!shouldAlert(target, status, now)) {
            log.debug("Suppressing repeat {} alert for target '{}'", status, target.getName());
            return;
        }
        Severity severity = evaluator.severityFor(status, check.getDaysUntilExpiry());
        alertDispatcher.dispatch(CertificateAlert.from(target, check, severity));
        target.recordAlert(status, now);
    }

    /** Alerts on every state change, then at most once per configured repeat interval. */
    private boolean shouldAlert(CertificateTarget target, CheckStatus status, Instant now) {
        if (target.getLastAlertedStatus() != status) {
            return true;
        }
        Instant lastAlertedAt = target.getLastAlertedAt();
        return lastAlertedAt == null || !lastAlertedAt.plus(properties.getAlertRepeatInterval()).isAfter(now);
    }
}

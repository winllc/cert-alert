package com.winllc.certalert.service;

import com.winllc.certalert.config.CertAlertProperties;
import com.winllc.certalert.domain.SyncJob;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Re-evaluates cached certificate expiry without reading the directory.
 *
 * <p>A certificate moves from valid to expiring to expired purely because time passes -
 * nothing in LDAP changes. Without this job a status would only be refreshed when its
 * entry happened to be re-scraped, so a nightly sweep would mean expiry alerts arriving up
 * to a day late and the search tables showing yesterday's answer.
 *
 * <p>Only certificates that could plausibly have moved are walked: not already expired,
 * and with a notAfter inside the warning window. On a directory of 100,000 entries that is
 * a small indexed slice rather than the whole table.
 */
@Service
public class CertificateRefreshService {

    private static final Logger log = LoggerFactory.getLogger(CertificateRefreshService.class);

    private static final int PAGE_SIZE = 500;

    private final CertificateRefreshPageProcessor pageProcessor;
    private final CertAlertProperties properties;
    private final SyncRunRecorder runRecorder;
    private final Clock clock;

    public CertificateRefreshService(
            CertificateRefreshPageProcessor pageProcessor,
            CertAlertProperties properties,
            SyncRunRecorder runRecorder,
            Clock clock) {
        this.pageProcessor = pageProcessor;
        this.properties = properties;
        this.runRecorder = runRecorder;
        this.clock = clock;
    }

    public DirectorySyncResult refresh() {
        Instant now = Instant.now(clock);
        long startedNanos = System.nanoTime();
        Long runId = runRecorder.started(SyncJob.REFRESH, now);
        Instant horizon = now.plus(properties.getWarningThresholdDays(), ChronoUnit.DAYS);

        int examined = 0;
        int alerts = 0;
        long cursor = 0;
        try {
            while (true) {
                CertificateRefreshPageProcessor.RefreshPage page =
                        pageProcessor.refreshPage(horizon, cursor, now, PAGE_SIZE);
                if (page.examined() == 0) {
                    break;
                }
                examined += page.examined();
                alerts += page.alerts();
                cursor = page.lastId();
            }
        } catch (RuntimeException e) {
            runRecorder.failed(runId, Instant.now(clock), e.toString());
            log.error("Certificate refresh failed", e);
            throw e;
        }

        DirectorySyncResult result = new DirectorySyncResult(
                SyncJob.REFRESH, examined, 0, 0, 0, alerts, 0, 0,
                Duration.ofNanos(System.nanoTime() - startedNanos));
        runRecorder.finished(runId, Instant.now(clock), result);
        log.info("Certificate refresh finished in {} ms: {} examined, {} alert(s)",
                result.duration().toMillis(), examined, alerts);
        return result;
    }
}

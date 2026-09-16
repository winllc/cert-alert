package com.winllc.certalert.alert;

import com.winllc.certalert.domain.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default notifier: writes alerts to the application log. Always-on unless explicitly
 * disabled, so a fresh install never silently drops alerts.
 */
@Component
@ConditionalOnProperty(prefix = "cert-alert.alerts.logging", name = "enabled", matchIfMissing = true)
public class LoggingAlertNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(LoggingAlertNotifier.class);

    @Override
    public void send(CertificateAlert alert) {
        if (alert.severity() == Severity.CRITICAL) {
            log.error("{}", alert.summary());
        } else {
            log.warn("{}", alert.summary());
        }
    }

    @Override
    public String channelName() {
        return "log";
    }
}

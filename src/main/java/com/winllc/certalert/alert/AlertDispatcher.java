package com.winllc.certalert.alert;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Fans an alert out to every configured notifier, isolating channel failures. */
@Component
public class AlertDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    private final List<AlertNotifier> notifiers;

    public AlertDispatcher(List<AlertNotifier> notifiers) {
        this.notifiers = notifiers;
    }

    public void dispatch(CertificateAlert alert) {
        if (notifiers.isEmpty()) {
            log.warn("No alert notifiers configured, dropping alert: {}", alert.summary());
            return;
        }
        for (AlertNotifier notifier : notifiers) {
            try {
                notifier.send(alert);
            } catch (RuntimeException e) {
                log.error("Alert channel '{}' failed to deliver alert for target '{}'",
                        notifier.channelName(), alert.targetName(), e);
            }
        }
    }
}

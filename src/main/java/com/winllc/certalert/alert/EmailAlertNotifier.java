package com.winllc.certalert.alert;

import com.winllc.certalert.domain.OutboundEmail;
import com.winllc.certalert.license.Licensing;
import com.winllc.certalert.service.MailOutbox;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Sends alerts by email. Disabled by default; enable with
 * {@code cert-alert.alerts.email.enabled=true} once {@code spring.mail.*} is configured.
 * Email delivery is a licensed feature ({@code notifications}): without it, or while the
 * license is not in full effect, the channel stands aside and the other channels still
 * deliver.
 *
 * <p>The message is queued in the {@link MailOutbox}, in the transaction of the sweep that
 * raised the alert, and sent once that commits: an alert is "sent" here when it is safely
 * queued, and a mail server that is down delays it rather than losing it.
 */
@Component
@ConditionalOnProperty(prefix = "cert-alert.alerts.email", name = "enabled", havingValue = "true")
public class EmailAlertNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(EmailAlertNotifier.class);

    private final MailOutbox outbox;
    private final EmailAlertProperties properties;
    private final Licensing licensing;
    private final Clock clock;

    public EmailAlertNotifier(
            MailOutbox outbox, EmailAlertProperties properties, Licensing licensing, Clock clock) {
        this.outbox = outbox;
        this.properties = properties;
        this.licensing = licensing;
        this.clock = clock;
    }

    @Override
    public boolean isActive() {
        return licensing.isEnabled("notifications");
    }

    @Override
    public void send(CertificateAlert alert) {
        if (properties.getTo().isEmpty()) {
            log.warn("Email alerts are enabled but no recipients are configured; skipping alert for '{}'",
                    alert.ownerName());
            return;
        }
        outbox.queue(new OutboundEmail(
                OutboundEmail.Kind.ALERT,
                properties.getFrom(),
                properties.getTo(),
                properties.getSubjectPrefix() + alert.ownerName() + " - " + alert.status(),
                body(alert),
                null,
                null,
                null,
                Instant.now(clock)));
        log.info("Queued an email alert for '{}' to {} recipient(s)", alert.ownerName(), properties.getTo().size());
    }

    private String body(CertificateAlert alert) {
        StringBuilder body = new StringBuilder(alert.summary()).append("\n\n");
        body.append("Owner:     ").append(alert.ownerType()).append(' ').append(alert.ownerName()).append('\n');
        body.append("DN:        ").append(alert.ownerDn()).append('\n');
        if (alert.contact() != null) {
            body.append("Contact:   ").append(alert.contact()).append('\n');
        }
        body.append("Subject:   ").append(alert.certificateSubject()).append('\n');
        body.append("Serial:    ").append(alert.serialNumber()).append('\n');
        body.append("Status:    ").append(alert.status()).append('\n');
        body.append("Severity:  ").append(alert.severity()).append('\n');
        if (alert.notAfter() != null) {
            body.append("Expires:   ").append(alert.notAfter()).append('\n');
        }
        if (alert.daysUntilExpiry() != null) {
            body.append("Days left: ").append(alert.daysUntilExpiry()).append('\n');
        }
        body.append("Raised at: ").append(alert.raisedAt()).append('\n');
        return body.toString();
    }

    @Override
    public String channelName() {
        return "email";
    }
}

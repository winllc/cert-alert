package com.winllc.certalert.alert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends alerts by email. Disabled by default; enable with
 * {@code cert-alert.alerts.email.enabled=true} once {@code spring.mail.*} is configured.
 */
@Component
@ConditionalOnProperty(prefix = "cert-alert.alerts.email", name = "enabled", havingValue = "true")
public class EmailAlertNotifier implements AlertNotifier {

    private static final Logger log = LoggerFactory.getLogger(EmailAlertNotifier.class);

    private final JavaMailSender mailSender;
    private final EmailAlertProperties properties;

    public EmailAlertNotifier(JavaMailSender mailSender, EmailAlertProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Override
    public void send(CertificateAlert alert) {
        if (properties.getTo().isEmpty()) {
            log.warn("Email alerts are enabled but no recipients are configured; skipping alert for '{}'",
                    alert.targetName());
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getFrom());
        message.setTo(properties.getTo().toArray(String[]::new));
        message.setSubject(properties.getSubjectPrefix() + alert.targetName() + " - " + alert.status());
        message.setText(body(alert));
        mailSender.send(message);
        log.info("Emailed alert for target '{}' to {} recipient(s)", alert.targetName(), properties.getTo().size());
    }

    private String body(CertificateAlert alert) {
        StringBuilder body = new StringBuilder(alert.summary()).append("\n\n");
        body.append("Target:    ").append(alert.targetName()).append('\n');
        body.append("Endpoint:  ").append(alert.hostname()).append(':').append(alert.port()).append('\n');
        body.append("Status:    ").append(alert.status()).append('\n');
        body.append("Severity:  ").append(alert.severity()).append('\n');
        if (alert.notAfter() != null) {
            body.append("Expires:   ").append(alert.notAfter()).append('\n');
        }
        if (alert.daysUntilExpiry() != null) {
            body.append("Days left: ").append(alert.daysUntilExpiry()).append('\n');
        }
        if (alert.detail() != null) {
            body.append("Detail:    ").append(alert.detail()).append('\n');
        }
        body.append("Raised at: ").append(alert.raisedAt()).append('\n');
        return body.toString();
    }

    @Override
    public String channelName() {
        return "email";
    }
}

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
                    alert.ownerName());
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.getFrom());
        message.setTo(properties.getTo().toArray(String[]::new));
        message.setSubject(properties.getSubjectPrefix() + alert.ownerName() + " - " + alert.status());
        message.setText(body(alert));
        mailSender.send(message);
        log.info("Emailed alert for '{}' to {} recipient(s)", alert.ownerName(), properties.getTo().size());
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

package com.winllc.certalert.service;

import com.winllc.certalert.config.NotificationProperties;
import com.winllc.certalert.domain.Notification;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Sends a round-up to the person who has to do something about it.
 *
 * <p>Distinct from the alert channels, which tell a fixed list of operators about every
 * alert as it happens. This writes to the contact of the certificate in question, once a
 * day, with everything of theirs that is expiring - which is the message somebody can
 * actually act on.
 *
 * <p>Absent a mail sender, or with sending switched off, this does nothing and says so
 * once. The notifications are still written and still show on the page.
 */
@Component
public class NotificationMailer {

    private static final Logger log = LoggerFactory.getLogger(NotificationMailer.class);

    private final Optional<JavaMailSender> mailSender;
    private final NotificationProperties properties;

    private boolean warned;
    private int sentThisRun;

    public NotificationMailer(Optional<JavaMailSender> mailSender, NotificationProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /**
     * @return whether it went out, so the caller can record that it did
     */
    public boolean send(NotificationRecipients.Recipient recipient, List<String> lines, Notification notification) {
        NotificationProperties.Email settings = properties.getEmail();
        if (!settings.isEnabled()) {
            return false;
        }
        if (mailSender.isEmpty()) {
            warnOnce("Notification email is enabled but no mail sender is configured");
            return false;
        }
        if (recipient.address() == null || recipient.address().isBlank()) {
            // A person the directory publishes no address for. They will see it on the page.
            return false;
        }
        if (sentThisRun >= settings.getMaxPerRun()) {
            warnOnce("Notification email stopped at " + settings.getMaxPerRun() + " messages for this run");
            return false;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(settings.getFrom());
        message.setTo(recipient.address());
        message.setSubject(settings.getSubjectPrefix() + notification.getMessage());
        message.setText(body(recipient, lines));
        try {
            mailSender.get().send(message);
            sentThisRun++;
            return true;
        } catch (RuntimeException e) {
            log.error("Could not email the expiry round-up to {}", recipient.address(), e);
            return false;
        }
    }

    /** Called at the start of a run, so the per-run cap means what it says. */
    public void beginRun() {
        sentThisRun = 0;
    }

    private String body(NotificationRecipients.Recipient recipient, List<String> lines) {
        StringBuilder body = new StringBuilder();
        body.append(recipient.name() == null ? "Hello" : "Hello " + recipient.name()).append(",\n\n");
        body.append("These certificates are expiring, or have expired, and you are the point of contact:\n\n");
        lines.forEach(line -> body.append("  - ").append(line).append('\n'));
        body.append("\nThis is a daily round-up from cert-alert. It reports what the directory publishes;\n");
        body.append("renewing a certificate means publishing the new one there.\n");
        return body.toString();
    }

    private void warnOnce(String message) {
        if (!warned) {
            log.warn(message);
            warned = true;
        }
    }
}

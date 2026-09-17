package com.winllc.certalert.service;

import com.winllc.certalert.config.NotificationProperties;
import com.winllc.certalert.domain.OwnerType;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Sends a round-up to the person who has to do something about it.
 *
 * <p>Distinct from the alert channels, which tell a fixed list of operators about every
 * alert as it happens. This writes to the contact of the certificate in question, once a
 * day, with everything of theirs that is expiring - which is the message somebody can
 * actually act on.
 *
 * <p>The message is rendered from Thymeleaf templates under {@code templates/email}, one
 * pair per kind of thing expiring: a person's own certificates and the certificates on
 * servers they are a point of contact for read differently and ask for different things, so
 * somebody who has both gets one of each rather than a single mixed list. Each goes out as
 * HTML with a plain-text alternative, and nothing in either is fetched when the message is
 * opened.
 *
 * <p>Absent a mail sender, or with sending switched off, this does nothing and says so
 * once. The notifications are still written and still show on the page.
 */
@Component
public class NotificationMailer {

    private static final Logger log = LoggerFactory.getLogger(NotificationMailer.class);

    private static final String USER_TEMPLATE = "email/expiring-user";
    private static final String SERVER_TEMPLATE = "email/expiring-server";

    private final Optional<JavaMailSender> mailSender;
    private final TemplateEngine templates;
    private final NotificationProperties properties;

    private boolean warned;
    private int sentThisRun;

    public NotificationMailer(
            Optional<JavaMailSender> mailSender, TemplateEngine templates, NotificationProperties properties) {
        this.mailSender = mailSender;
        this.templates = templates;
        this.properties = properties;
    }

    /**
     * Writes to one person about everything of theirs that is expiring.
     *
     * @return whether anything went out, so the caller can record that it did
     */
    public boolean send(NotificationRecipients.Recipient recipient, List<ExpiryDigest.Entry> entries) {
        NotificationProperties.Email settings = properties.getEmail();
        if (!settings.isEnabled() || entries.isEmpty()) {
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

        boolean sent = false;
        for (ExpiryDigest digest : ExpiryDigest.split(greeting(recipient), entries)) {
            if (sentThisRun >= settings.getMaxPerRun()) {
                warnOnce("Notification email stopped at " + settings.getMaxPerRun() + " messages for this run");
                break;
            }
            sent |= send(settings, recipient.address(), digest);
        }
        return sent;
    }

    /**
     * What to call them, or null. A point of contact the directory publishes as a bare
     * address has that address for a name, and writing "Hello ops@example.gov" to a role
     * mailbox reads worse than not greeting it by name at all.
     */
    private static String greeting(NotificationRecipients.Recipient recipient) {
        String name = recipient.name();
        if (name == null || name.isBlank() || name.equalsIgnoreCase(recipient.address())) {
            return null;
        }
        return name;
    }

    /** Called at the start of a run, so the per-run cap means what it says. */
    public void beginRun() {
        sentThisRun = 0;
    }

    private boolean send(NotificationProperties.Email settings, String address, ExpiryDigest digest) {
        String template = digest.getOwnerType() == OwnerType.USER ? USER_TEMPLATE : SERVER_TEMPLATE;
        try {
            Context context = new Context(Locale.ENGLISH);
            context.setVariable("digest", digest);

            MimeMessage message = mailSender.get().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(settings.getFrom());
            helper.setTo(address);
            helper.setSubject(settings.getSubjectPrefix() + digest.getHeadline());
            // Text first, HTML second: the order MimeMessageHelper wants for an alternative.
            helper.setText(templates.process(template + ".txt", context), templates.process(template, context));

            mailSender.get().send(message);
            sentThisRun++;
            return true;
        } catch (MessagingException | RuntimeException e) {
            log.error("Could not email the expiry round-up to {}", address, e);
            return false;
        }
    }

    private void warnOnce(String message) {
        if (!warned) {
            log.warn(message);
            warned = true;
        }
    }
}

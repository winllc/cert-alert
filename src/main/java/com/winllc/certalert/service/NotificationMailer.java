package com.winllc.certalert.service;

import com.winllc.certalert.config.NotificationProperties;
import com.winllc.certalert.domain.OwnerType;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
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
    private boolean dryRunThisRun;
    /** What a dry run built, in the order it built it. Empty on a run that really sent. */
    private final List<Rendered> renderedThisRun = new ArrayList<>();
    /** What could not be built or sent, and why. */
    private final List<Failure> failuresThisRun = new ArrayList<>();

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
        if (entries.isEmpty()) {
            return false;
        }
        // A rehearsal runs with sending switched off, which is the case it is most wanted
        // in: the question "what would this send" is asked before email is turned on, not
        // after. What comes back says which it was, so nothing reads as having gone out.
        if (!settings.isEnabled() && !dryRunThisRun) {
            return false;
        }
        // A dry run needs no transport, which is rather the point: the question it answers -
        // how many messages, and what do they say - is one to settle before there is a mail
        // server to answer it against.
        if (mailSender.isEmpty() && !dryRunThisRun) {
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
        beginRun(false);
    }

    /**
     * @param dryRun forces this run to build its messages and send none of them, on top of
     *     the configured mode - so somebody can ask what a round-up would do without
     *     putting the whole deployment into dry run to find out
     */
    public void beginRun(boolean dryRun) {
        sentThisRun = 0;
        dryRunThisRun = dryRun || properties.getEmail().isDryRun();
        renderedThisRun.clear();
        failuresThisRun.clear();
    }

    /**
     * How many messages this run has handed over - or built and dropped, on a dry run.
     *
     * <p>Not the same as the number of people written to: somebody who holds an expiring
     * certificate and looks after an expiring server gets one message about each, and "how
     * many emails" is a question about messages.
     */
    public int sent() {
        return sentThisRun;
    }

    /** Whether there is anything to send with, which {@code spring.mail.*} decides. */
    public boolean hasSender() {
        return mailSender.isPresent();
    }

    /** Whether messages are being built and thrown away rather than sent. */
    public boolean isDryRun() {
        return dryRunThisRun;
    }

    /** What the run just built, for showing somebody what would have gone out. */
    public List<Rendered> rendered() {
        return List.copyOf(renderedThisRun);
    }

    /**
     * What went wrong, for saying so where it will be read.
     *
     * <p>A message that cannot be built used to leave a line in the log and nothing else -
     * so a deployment with a template of its own that does not compile saw "nothing would be
     * sent" and no way to find out why. A rehearsal exists to surface exactly that.
     */
    public List<Failure> failures() {
        return List.copyOf(failuresThisRun);
    }

    /**
     * One message as it would have been sent.
     *
     * @param to the address it was addressed to
     * @param subject the subject line, prefix and all
     * @param text the plain-text alternative
     * @param html the HTML body
     */
    public record Rendered(String to, String subject, String text, String html) {}

    /**
     * One message that could not be built or sent.
     *
     * @param to who it was for
     * @param reason what the failure said, as short as it comes
     */
    public record Failure(String to, String reason) {}

    private boolean send(NotificationProperties.Email settings, String address, ExpiryDigest digest) {
        String template = digest.getOwnerType() == OwnerType.USER ? USER_TEMPLATE : SERVER_TEMPLATE;
        try {
            Context context = new Context(Locale.ENGLISH);
            context.setVariable("digest", digest);

            String subject = settings.getSubjectPrefix() + digest.getHeadline();
            String text = templates.process(template + ".txt", context);
            String html = templates.process(template, context);

            if (dryRunThisRun) {
                // Built and dropped, which is the point: everything that could go wrong in
                // the building has already happened by here.
                renderedThisRun.add(new Rendered(address, subject, text, html));
                sentThisRun++;
                return true;
            }

            MimeMessage message = mailSender.get().createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(settings.getFrom());
            helper.setTo(address);
            helper.setSubject(subject);
            // Text first, HTML second: the order MimeMessageHelper wants for an alternative.
            helper.setText(text, html);

            mailSender.get().send(message);
            sentThisRun++;
            return true;
        } catch (MessagingException | RuntimeException e) {
            log.error("Could not email the expiry round-up to {}", address, e);
            failuresThisRun.add(new Failure(address, describe(e)));
            return false;
        }
    }

    /**
     * The shortest true thing about a failure.
     *
     * <p>The cause where there is one: a template that will not compile arrives wrapped in
     * a Thymeleaf exception whose own message names the wrapper, and the line underneath is
     * the one that says which template and which line.
     */
    private static String describe(Exception e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        String message = cause.getMessage();
        return (message == null || message.isBlank() ? cause.getClass().getSimpleName() : message)
                .lines()
                .findFirst()
                .orElse(cause.getClass().getSimpleName());
    }

    private void warnOnce(String message) {
        if (!warned) {
            log.warn(message);
            warned = true;
        }
    }
}

package com.winllc.certalert.service;

import com.winllc.certalert.cluster.JobLocks;
import com.winllc.certalert.config.MailOutboxProperties;
import com.winllc.certalert.domain.OutboundEmail;
import com.winllc.certalert.repository.NotificationRepository;
import com.winllc.certalert.repository.OutboundEmailRepository;
import jakarta.annotation.PreDestroy;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every email this application sends goes through here: written down first, sent after.
 *
 * <p><b>Queued in the caller's transaction.</b> A round-up's message is written in the same
 * transaction as its notification, an alert's in the same transaction as the sweep that
 * raised it - so the message exists exactly when the thing it reports does, and a rollback
 * takes both.
 *
 * <p><b>Sent after the commit, outside any transaction.</b> Talking to a mail server inside
 * a database transaction holds the transaction - and its locks and its connection - for as
 * long as the mail server takes, and a mail server that is down turns into a sweep that
 * fails, or a message that is lost. Here the send happens once the transaction is over:
 * straight away on a thread of its own, or, for the round-up, by the caller as soon as its
 * own transaction is done so it can say what went.
 *
 * <p><b>Retried until it is too old to matter.</b> A message that cannot be sent stays
 * {@code PENDING} and is tried again after {@code retry-initial}, then twice as long, up to
 * {@code retry-max} apart, until it is {@code give-up-after} old; then it is marked
 * {@code FAILED} and an error is logged. Nothing is lost to an outage shorter than that.
 *
 * <p><b>Newer replaces older.</b> A message queued with a supersede key retires any message
 * with the same key still waiting: a mail server back after three days delivers today's
 * round-up, not three of them.
 *
 * <p><b>Sent by one replica at a time.</b> A lock in the database (see {@link JobLocks}),
 * as well as one in this process, keeps a background pass and an explicit delivery - here
 * or on another replica - from sending the same message twice. An explicit delivery that
 * finds another replica sending leaves its messages to the background, which says so.
 */
@Service
@EnableScheduling
public class MailOutbox {

    private static final Logger log = LoggerFactory.getLogger(MailOutbox.class);

    /** The lock every send holds, on whichever replica. */
    static final String LOCK = "mail-outbox";

    /** Messages sent per pass, so one pass after a long outage does not run unbounded. */
    private static final int BATCH = 50;

    private final OutboundEmailRepository outbox;
    private final NotificationRepository notifications;
    private final Optional<JavaMailSender> mailSender;
    private final MailOutboxProperties properties;
    private final Clock clock;
    private final TransactionTemplate joining;
    private final TransactionTemplate independent;
    private final JobLocks locks;
    private final ReentrantLock sending = new ReentrantLock();
    private final AtomicBoolean nudged = new AtomicBoolean();
    private final ExecutorService background = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mail-outbox");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean warnedNoSender;

    public MailOutbox(
            OutboundEmailRepository outbox,
            NotificationRepository notifications,
            Optional<JavaMailSender> mailSender,
            MailOutboxProperties properties,
            PlatformTransactionManager transactions,
            Clock clock,
            JobLocks locks) {
        this.outbox = outbox;
        this.notifications = notifications;
        this.mailSender = mailSender;
        this.properties = properties;
        this.clock = clock;
        this.locks = locks;
        this.joining = new TransactionTemplate(transactions);
        this.independent = new TransactionTemplate(transactions);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Messages that fail one after another before a pass stops and leaves the rest for later. */
    private static final int FAILURES_PER_PASS = 3;

    /**
     * Writes a message down, in the caller's transaction where there is one, and sends it
     * in the background once that commits.
     *
     * @return its id
     */
    public Long queue(OutboundEmail message) {
        return queue(message, true);
    }

    /**
     * @param sendAfterCommit false for a caller that will {@link #deliver} it itself, once
     *     its transaction is done, because it has to report what went
     */
    public Long queue(OutboundEmail message, boolean sendAfterCommit) {
        return joining.execute(status -> {
            if (message.getSupersedeKey() != null) {
                int replaced = outbox.supersede(
                        message.getSupersedeKey(), OutboundEmail.State.PENDING, OutboundEmail.State.SUPERSEDED);
                if (replaced > 0) {
                    log.info("Replaced {} unsent message(s) for {} with a newer one", replaced, message.getSupersedeKey());
                }
            }
            Long id = outbox.save(message).getId();
            if (sendAfterCommit) {
                sendAfterCommit();
            }
            return id;
        });
    }

    /** How one explicit delivery went. */
    /**
     * @param deferred whether they were left to the background instead, because the caller
     *     was still inside a transaction
     */
    public record Delivery(int sent, List<Failure> failures, boolean deferred) {

        /** One message that did not go this time, and is still queued to be tried again. */
        public record Failure(List<String> to, String reason) {}

        static final Delivery NOTHING = new Delivery(0, List.of(), false);
    }

    /**
     * Sends these messages now, on the calling thread, and says how that went. For a caller
     * that has to report what it sent - the round-up, which answers a button on the page.
     * Must be called outside a transaction: the whole point is not to hold one while the
     * mail server thinks. Inside one, they are left to the background instead.
     */
    public Delivery deliver(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Delivery.NOTHING;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            sendAfterCommit();
            return new Delivery(0, List.of(), true);
        }
        sending.lock();
        try {
            return locks.runExclusively(LOCK, () -> {
                        int sent = 0;
                        List<Delivery.Failure> failures = new ArrayList<>();
                        for (Long id : ids) {
                            Attempt attempt = attempt(id);
                            if (attempt.sent() || attempt.alreadySent()) {
                                sent++;
                            } else if (attempt.message() != null) {
                                failures.add(new Delivery.Failure(attempt.message().getRecipients(), attempt.error()));
                            }
                        }
                        return new Delivery(sent, failures, false);
                    })
                    // Another replica is sending: these go with its pass or the next one.
                    .orElseGet(() -> new Delivery(0, List.of(), true));
        } finally {
            sending.unlock();
        }
    }

    /**
     * Sends whatever is due, and clears away what is long finished with. Run on a schedule
     * for the retries, and straight after a commit that queued something.
     *
     * @return how many went
     */
    public int deliverDue() {
        if (!sending.tryLock()) {
            // Another pass is sending; it reads again until nothing is due, so whatever
            // asked for this one is covered.
            return 0;
        }
        try {
            return locks.runExclusively(LOCK, () -> {
                int sent = 0;
                int failedInARow = 0;
                pass:
                while (true) {
                    List<Long> due = outbox.findDue(
                            OutboundEmail.State.PENDING, Instant.now(clock), PageRequest.of(0, BATCH));
                    if (due.isEmpty() || mailSender.isEmpty()) {
                        if (!due.isEmpty()) {
                            attempt(due.getFirst());
                        }
                        break;
                    }
                    for (Long id : due) {
                        Attempt attempt = attempt(id);
                        if (attempt.sent()) {
                            sent++;
                            failedInARow = 0;
                        } else if (attempt.message() != null && ++failedInARow >= FAILURES_PER_PASS) {
                            // A mail server that is down fails every message the same way,
                            // each after a full timeout. The rest wait for the next pass.
                            break pass;
                        }
                    }
                    if (due.size() < BATCH) {
                        break;
                    }
                }
                independent.executeWithoutResult(status -> outbox.deleteFinishedBefore(
                        OutboundEmail.State.PENDING, Instant.now(clock).minus(properties.getKeep())));
                return sent;
            }).orElse(0);
        } finally {
            sending.unlock();
        }
    }

    @Scheduled(
            fixedDelayString = "${cert-alert.mail.outbox.poll-interval:PT30S}",
            initialDelayString = "${cert-alert.mail.outbox.poll-interval:PT30S}")
    public void poll() {
        if (!properties.isBackground()) {
            return;
        }
        try {
            deliverDue();
        } catch (RuntimeException e) {
            log.error("Sending queued email failed; it is tried again on the next pass", e);
        }
    }

    /** What is waiting, for the metrics. */
    public long pending() {
        return outbox.countByState(OutboundEmail.State.PENDING);
    }

    /** What was given up on and is still on record, for the metrics. */
    public long failed() {
        return outbox.countByState(OutboundEmail.State.FAILED);
    }

    @PreDestroy
    void stop() {
        background.shutdownNow();
    }

    /** Once the current transaction commits - or now, where there is none - send what is due. */
    private void sendAfterCommit() {
        if (!properties.isBackground()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    nudge();
                }
            });
        } else {
            nudge();
        }
    }

    /**
     * On the outbox's own thread, so the committing thread - a sweep, a request - goes on
     * with its work, and the connection its transaction held is released rather than kept
     * while the mail server answers. Requests arriving while one is waiting are one request.
     */
    private void nudge() {
        if (nudged.compareAndSet(false, true)) {
            try {
                background.execute(() -> {
                    nudged.set(false);
                    try {
                        deliverDue();
                    } catch (RuntimeException e) {
                        log.error("Sending queued email failed; it is tried again on the next pass", e);
                    }
                });
            } catch (RuntimeException e) {
                nudged.set(false);
                log.debug("The outbox's thread is not taking work; the next pass sends it", e);
            }
        }
    }

    private record Attempt(OutboundEmail message, boolean sent, String error, boolean alreadySent) {

        Attempt(OutboundEmail message, boolean sent, String error) {
            this(message, sent, error, false);
        }
    }

    /** One message, if it is still waiting: sent, and what became of it recorded. */
    private Attempt attempt(Long id) {
        OutboundEmail message = outbox.findById(id).orElse(null);
        if (message == null || message.getState() != OutboundEmail.State.PENDING) {
            // A background pass may have got there first.
            boolean sent = message != null && message.getState() == OutboundEmail.State.SENT;
            return new Attempt(null, false, null, sent);
        }
        if (mailSender.isEmpty()) {
            if (!warnedNoSender) {
                warnedNoSender = true;
                log.warn("Email is queued but no mail server is configured (spring.mail.host); it waits until one is");
            }
            return new Attempt(message, false, "no mail server is configured (spring.mail.host)");
        }
        Instant now = Instant.now(clock);
        try {
            send(message);
        } catch (MessagingException | RuntimeException e) {
            String error = describe(e);
            record(id, now, error);
            return new Attempt(message, false, error);
        }
        independent.executeWithoutResult(status -> outbox.findById(id).ifPresent(row -> {
            row.sent(now);
            if (row.getNotificationId() != null) {
                notifications.markEmailed(row.getNotificationId(), now);
            }
        }));
        return new Attempt(message, true, null);
    }

    /** A failure: tried again later, or given up on once it is old enough. */
    private void record(Long id, Instant now, String error) {
        independent.executeWithoutResult(status -> outbox.findById(id).ifPresent(row -> {
            if (!now.isBefore(row.getCreatedAt().plus(properties.getGiveUpAfter()))) {
                row.gaveUp(error);
                log.error("Gave up on the {} email \"{}\" to {} after {} attempt(s) over {}: {}",
                        row.getKind(), row.getSubject(), row.getRecipients(), row.getAttempts(),
                        properties.getGiveUpAfter(), error);
                return;
            }
            Instant retryAt = now.plus(backoff(row.getAttempts() + 1));
            row.failed(error, retryAt);
            log.warn("Could not send the {} email \"{}\" to {} (attempt {}); trying again at {}: {}",
                    row.getKind(), row.getSubject(), row.getRecipients(), row.getAttempts(), retryAt, error);
        }));
    }

    private Duration backoff(int failures) {
        return backoff(failures, properties.getRetryInitial(), properties.getRetryMax());
    }

    /** {@code initial} after the first failure, doubling, never more than {@code max}. */
    static Duration backoff(int failures, Duration initial, Duration max) {
        Duration wait = initial;
        for (int i = 1; i < failures && wait.compareTo(max) < 0; i++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(max) > 0 ? max : wait;
    }

    private void send(OutboundEmail message) throws MessagingException {
        JavaMailSender sender = mailSender.orElseThrow();
        MimeMessage mime = sender.createMimeMessage();
        boolean alternative = message.getHtmlBody() != null;
        MimeMessageHelper helper = new MimeMessageHelper(mime, alternative, "UTF-8");
        helper.setFrom(message.getSender());
        helper.setTo(message.getRecipients().toArray(String[]::new));
        helper.setSubject(message.getSubject());
        if (alternative) {
            // Text first, HTML second: the order MimeMessageHelper wants for an alternative.
            helper.setText(message.getTextBody(), message.getHtmlBody());
        } else {
            helper.setText(message.getTextBody());
        }
        sender.send(mime);
    }

    /** The cause's message where there is one: a refused connection arrives wrapped. */
    private static String describe(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return (message == null || message.isBlank() ? cause.getClass().getSimpleName() : message)
                .lines()
                .findFirst()
                .orElse(cause.getClass().getSimpleName());
    }
}

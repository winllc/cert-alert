package com.winllc.certalert.service;

import com.winllc.certalert.alert.CertificateAlert;
import com.winllc.certalert.config.NotificationProperties;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.Notification;
import com.winllc.certalert.domain.NotificationKind;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.Severity;
import com.winllc.certalert.repository.CachedCertificateRepository;
import com.winllc.certalert.repository.NotificationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells people about their certificates.
 *
 * <p>Two ways, deliberately different. A certificate crossing into a bad state is told
 * about the moment a sweep notices, one notification per contact, and it waits on the page
 * for them. Everything that is expiring goes out once a day as a round-up, by email, to the
 * address the certificate's contact actually reads - which is a different question from
 * the alert channels, where a fixed list of operators hears about everything.
 *
 * <p>Nobody is told the same thing twice inside {@code repeat-after}. A nightly sweep and a
 * certificate that stays expiring for a month would otherwise mean thirty notifications.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** A digest reads a page of certificates at a time; this is a bound, not a target. */
    private static final int DIGEST_SCAN_LIMIT = 10_000;

    private final NotificationRepository notifications;
    private final CachedCertificateRepository certificates;
    private final NotificationRecipients recipients;
    private final NotificationMailer mailer;
    private final NotificationSettingsService settings;
    private final NotificationProperties properties;
    private final Clock clock;

    public NotificationService(
            NotificationRepository notifications,
            CachedCertificateRepository certificates,
            NotificationRecipients recipients,
            NotificationMailer mailer,
            NotificationSettingsService settings,
            NotificationProperties properties,
            Clock clock) {
        this.notifications = notifications;
        this.certificates = certificates;
        this.recipients = recipients;
        this.mailer = mailer;
        this.settings = settings;
        this.properties = properties;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------------------
    // What somebody sees when they sign in
    // -------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<Notification> forRecipient(Long userId, Pageable pageable) {
        return notifications.findByRecipientUserIdOrderByCreatedAtDescIdDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notifications.countByRecipientUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markRead(Long userId, Long notificationId) {
        notifications
                .findByIdAndRecipientUserId(notificationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("No notification with id " + notificationId))
                .markRead(Instant.now(clock));
    }

    @Transactional
    public int markAllRead(Long userId) {
        return notifications.markAllRead(userId, Instant.now(clock));
    }

    // -------------------------------------------------------------------------------------
    // Raised as things happen
    // -------------------------------------------------------------------------------------

    /**
     * One notification per contact for a certificate that has just changed state. Called
     * from the alert dispatcher, so it runs inside whichever transaction noticed.
     */
    @Transactional
    public int notifyAbout(CertificateAlert alert) {
        if (!properties.isEnabled()) {
            return 0;
        }
        Instant now = Instant.now(clock);
        Instant since = now.minus(properties.getRepeatAfter());
        AuditEvent.SubjectRef subject =
                new AuditEvent.SubjectRef(alert.ownerType(), alert.ownerId(), alert.ownerDn(), alert.ownerName());

        List<Notification> created = new ArrayList<>();
        for (NotificationRecipients.Recipient recipient : resolve(alert.ownerType(), alert.ownerId())) {
            if (alreadyTold(recipient, alert.certificateFingerprint(), alert.severity(),
                    NotificationKind.CERTIFICATE_STATUS, since)) {
                continue;
            }
            created.add(new Notification(
                    recipient.userId(),
                    recipient.address(),
                    NotificationKind.CERTIFICATE_STATUS,
                    subject,
                    alert.certificateFingerprint(),
                    alert.severity(),
                    alert.summary(),
                    now));
        }
        notifications.saveAll(created);
        return created.size();
    }

    /**
     * One notification per contact for a certificate an authority has revoked.
     *
     * <p>Told about separately from everything else, and not held for the daily round-up.
     * Expiry is a date on the certificate that anybody can read and that arrives on a known
     * day; a revocation is a decision made elsewhere, that the certificate does not mention,
     * and that is already in force by the time this finds out.
     *
     * @return how many people were told
     */
    @Transactional
    public int notifyRevoked(CachedCertificate certificate, AuditEvent.SubjectRef subject, OwnerType type) {
        if (!properties.isEnabled() || subject.id() == null) {
            return 0;
        }
        Instant now = Instant.now(clock);
        Instant since = now.minus(properties.getRepeatAfter());
        String summary = "Certificate %s was revoked%s%s".formatted(
                certificate.getSerialNumber(),
                certificate.getRevokedAt() == null ? "" : " on " + certificate.getRevokedAt(),
                certificate.getRevocationReason() == null ? "" : " (" + certificate.getRevocationReason() + ")");

        List<Notification> created = new ArrayList<>();
        for (NotificationRecipients.Recipient recipient : resolve(type, subject.id())) {
            if (alreadyTold(recipient, certificate.getSha256Fingerprint(), Severity.CRITICAL,
                    NotificationKind.CERTIFICATE_REVOKED, since)) {
                continue;
            }
            created.add(new Notification(
                    recipient.userId(),
                    recipient.address(),
                    NotificationKind.CERTIFICATE_REVOKED,
                    subject,
                    certificate.getSha256Fingerprint(),
                    Severity.CRITICAL,
                    summary,
                    now));
        }
        notifications.saveAll(created);
        return created.size();
    }

    // -------------------------------------------------------------------------------------
    // The daily round-up
    // -------------------------------------------------------------------------------------

    /**
     * Gathers everything expiring inside the window, one round-up per person, and sends it.
     *
     * @return how many people were told
     */
    @Transactional
    public DigestResult digest() {
        NotificationProperties.Digest digestSettings = properties.getDigest();
        if (!properties.isEnabled()) {
            return DigestResult.NOTHING;
        }
        Instant now = Instant.now(clock);
        // How far ahead to look is somebody's decision, made in the UI, and it may be
        // further than the window certificates are marked EXPIRING_SOON in.
        int leadDays = settings.leadDays();
        Instant horizon = now.plus(Duration.ofDays(leadDays));
        // Nothing has a notAfter before this, so it excludes nothing; the current instant in
        // its place is what leaves out what has already expired.
        Instant floor = digestSettings.isIncludeExpired() ? Instant.EPOCH : now;

        List<CachedCertificate> expiring =
                certificates.findExpiringBetween(floor, horizon, PageRequest.of(0, DIGEST_SCAN_LIMIT));
        if (expiring.isEmpty()) {
            log.debug("Expiry digest: nothing expiring in the next {} day(s)", leadDays);
            return DigestResult.NOTHING;
        }

        // Grouped by who has to do something about it, rather than by what is expiring:
        // one person with eight expiring certificates should get one email, not eight.
        Map<String, Round> rounds = new LinkedHashMap<>();
        for (CachedCertificate certificate : expiring) {
            OwnerType type = certificate.getUser() != null ? OwnerType.USER : OwnerType.SERVER;
            AuditEvent.SubjectRef subject = subjectOf(certificate);
            if (subject.id() == null) {
                continue;
            }
            for (NotificationRecipients.Recipient recipient : resolve(type, subject.id())) {
                rounds.computeIfAbsent(keyOf(recipient), key -> new Round(recipient))
                        .add(certificate, subject, type, now);
            }
        }

        int told = 0;
        int emailed = 0;
        mailer.beginRun();
        for (Round round : rounds.values()) {
            Notification notification = round.toNotification(now);
            notifications.save(notification);
            told++;
            if (mailer.send(round.recipient, round.entries)) {
                notification.markEmailed(now);
                emailed++;
            }
        }
        log.info("Expiry digest: {} certificate(s) expiring in the next {} day(s), {} person(s) told, {} emailed",
                expiring.size(), leadDays, told, emailed);
        return new DigestResult(expiring.size(), told, emailed);
    }

    /** Removes notifications that have been read and gone stale, when asked to. */
    @Transactional
    public int trim() {
        NotificationProperties.Retention retention = properties.getRetention();
        if (!retention.isEnabled()) {
            return 0;
        }
        int removed = notifications.deleteReadBefore(Instant.now(clock).minus(retention.getAfter()));
        if (removed > 0) {
            log.info("Removed {} read notification(s)", removed);
        }
        return removed;
    }

    /** What one run of the digest did. */
    public record DigestResult(int certificates, int peopleTold, int emailsSent) {
        static final DigestResult NOTHING = new DigestResult(0, 0, 0);
    }

    private List<NotificationRecipients.Recipient> resolve(OwnerType type, Long ownerId) {
        if (ownerId == null) {
            return List.of();
        }
        return type == OwnerType.USER ? recipients.forUser(ownerId) : recipients.forServer(ownerId);
    }

    private boolean alreadyTold(
            NotificationRecipients.Recipient recipient,
            String fingerprint,
            Severity severity,
            NotificationKind kind,
            Instant since) {

        if (fingerprint == null) {
            return false;
        }
        if (recipient.userId() != null) {
            return notifications.existsByRecipientUserIdAndCertificateFingerprintAndSeverityAndKindAndCreatedAtAfter(
                    recipient.userId(), fingerprint, severity, kind, since);
        }
        return notifications.existsByRecipientAddressAndCertificateFingerprintAndSeverityAndKindAndCreatedAtAfter(
                recipient.address(), fingerprint, severity, kind, since);
    }

    private static String keyOf(NotificationRecipients.Recipient recipient) {
        return recipient.userId() != null ? "user:" + recipient.userId() : "address:" + recipient.address();
    }

    private static AuditEvent.SubjectRef subjectOf(CachedCertificate certificate) {
        DirectoryUser user = certificate.getUser();
        if (user != null) {
            return AuditEvent.SubjectRef.of(user);
        }
        DirectoryServer server = certificate.getServer();
        return server == null
                ? new AuditEvent.SubjectRef(OwnerType.SERVER, null, "unknown", null)
                : AuditEvent.SubjectRef.of(server);
    }

    /** One person's round-up, built up as the expiring certificates are walked. */
    private static final class Round {

        private final NotificationRecipients.Recipient recipient;
        private final List<ExpiryDigest.Entry> entries = new ArrayList<>();
        private int expired;
        private int expiring;
        private Severity severity = Severity.INFO;

        private Round(NotificationRecipients.Recipient recipient) {
            this.recipient = recipient;
        }

        private void add(
                CachedCertificate certificate, AuditEvent.SubjectRef subject, OwnerType ownerType, Instant now) {

            ExpiryDigest.Entry entry = ExpiryDigest.Entry.of(certificate, subject, ownerType, now);
            if (entry.isExpired()) {
                expired++;
                severity = Severity.CRITICAL;
            } else {
                expiring++;
                if (severity == Severity.INFO) {
                    severity = Severity.WARNING;
                }
            }
            entries.add(entry);
        }

        private Notification toNotification(Instant now) {
            String message = "%s %s".formatted(
                    summary(),
                    entries.size() == 1
                            ? "(" + entries.getFirst().getSummary() + ")"
                            : "across " + entries.size() + " certificate(s)");
            return new Notification(
                    recipient.userId(),
                    recipient.address(),
                    NotificationKind.EXPIRY_DIGEST,
                    // A round-up is about everything it names, so it hangs off none of them
                    // in particular; the entries say what.
                    new AuditEvent.SubjectRef(OwnerType.USER, recipient.userId(), "digest", recipient.name()),
                    null,
                    severity,
                    truncate(message),
                    now);
        }

        private String summary() {
            if (expired > 0 && expiring > 0) {
                return "%d certificate(s) expired and %d expiring".formatted(expired, expiring);
            }
            return expired > 0
                    ? "%d certificate(s) expired".formatted(expired)
                    : "%d certificate(s) expiring".formatted(expiring);
        }

        private static String truncate(String message) {
            return message.length() <= 1000 ? message : message.substring(0, 997) + "...";
        }
    }
}

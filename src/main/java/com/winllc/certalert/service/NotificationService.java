package com.winllc.certalert.service;

import com.winllc.certalert.alert.CertificateAlert;
import com.winllc.certalert.config.CredentialProperties;
import com.winllc.certalert.config.NotificationProperties;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.DirectoryEntry;
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
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /** How many built messages a dry run hands back, so a large estate cannot return a book. */
    private static final int DRY_RUN_PREVIEW_LIMIT = 25;

    private final NotificationRepository notifications;
    private final CachedCertificateRepository certificates;
    private final NotificationRecipients recipients;
    private final NotificationMailer mailer;
    private final NotificationSettingsService settings;
    private final NotificationProperties properties;
    private final CredentialProperties credentialProperties;
    private final Clock clock;

    public NotificationService(
            NotificationRepository notifications,
            CachedCertificateRepository certificates,
            NotificationRecipients recipients,
            NotificationMailer mailer,
            NotificationSettingsService settings,
            NotificationProperties properties,
            CredentialProperties credentialProperties,
            Clock clock) {
        this.notifications = notifications;
        this.certificates = certificates;
        this.recipients = recipients;
        this.mailer = mailer;
        this.settings = settings;
        this.properties = properties;
        this.credentialProperties = credentialProperties;
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

        // A person's pair crosses into a bad state together, because it was issued together.
        // Asked about the whole credential, the second half finds the first half's
        // notification already there and adds nothing.
        List<String> credential = credentialFingerprintsOf(alert);

        List<Notification> created = new ArrayList<>();
        for (NotificationRecipients.Recipient recipient : resolve(alert.ownerType(), alert.ownerId())) {
            if (alreadyTold(recipient, credential, alert.severity(),
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
        return digest(false);
    }

    /**
     * @param dryRun build every message and send none, writing nothing down either - so the
     *     question "who would hear from this, and what would it say" can be asked of a real
     *     directory without anybody's inbox or the notifications page being the answer
     */
    @Transactional
    public DigestResult digest(boolean dryRun) {
        NotificationProperties.Digest digestSettings = properties.getDigest();
        // A run that found nothing still says which kind of run it was: a rehearsal
        // reported as a real run reads as one that went out and sent nothing.
        if (!properties.isEnabled()) {
            return nothing(dryRun, "Notifications are switched off in the configuration");
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
            return nothing(dryRun, "Nothing is expiring in the next " + leadDays + " day(s)");
        }

        // Grouped by who has to do something about it, rather than by what is expiring:
        // one person with eight expiring certificates should get one email, not eight.
        Map<String, Round> rounds = new LinkedHashMap<>();
        // What each owner still depends on, worked out once per owner rather than per
        // certificate: the entry is the same object for all of its own certificates.
        Map<DirectoryEntry, Set<Long>> dependedOn = new IdentityHashMap<>();
        int superseded = 0;

        // Held per entry before anything is reported, because a person's signing and key
        // encipherment certificates are one credential and have to be seen together to be
        // recognised as one. The order they arrived in - soonest to expire first - is kept.
        Map<Long, List<CachedCertificate>> byEntry = new LinkedHashMap<>();
        Map<Long, AuditEvent.SubjectRef> subjects = new LinkedHashMap<>();
        Map<Long, OwnerType> types = new LinkedHashMap<>();

        for (CachedCertificate certificate : expiring) {
            OwnerType type = certificate.getUser() != null ? OwnerType.USER : OwnerType.SERVER;
            AuditEvent.SubjectRef subject = subjectOf(certificate);
            if (subject.id() == null) {
                continue;
            }
            DirectoryEntry owner = certificate.getUser() != null ? certificate.getUser() : certificate.getServer();
            if (owner != null
                    && !dependedOn.computeIfAbsent(owner, this::stillDependedOn)
                            .contains(certificate.getId())) {
                superseded++;
                continue;
            }
            // Users and servers cannot collide: an id is unique within its own table, and
            // this key carries which table it came from.
            long key = type == OwnerType.USER ? subject.id() : -subject.id();
            byEntry.computeIfAbsent(key, id -> new ArrayList<>()).add(certificate);
            subjects.putIfAbsent(key, subject);
            types.putIfAbsent(key, type);
        }

        int credentials = 0;
        for (Map.Entry<Long, List<CachedCertificate>> owned : byEntry.entrySet()) {
            AuditEvent.SubjectRef subject = subjects.get(owned.getKey());
            OwnerType type = types.get(owned.getKey());
            for (Credential credential : Credential.group(owned.getValue(), credentialProperties.getPairWindow())) {
                credentials++;
                for (NotificationRecipients.Recipient recipient : resolve(type, subject.id())) {
                    rounds.computeIfAbsent(keyOf(recipient), key -> new Round(recipient))
                            .add(credential, subject, type, now);
                }
            }
        }

        int told = 0;
        int unaddressed = 0;
        mailer.beginRun(dryRun);
        boolean rehearsing = mailer.isDryRun();
        for (Round round : rounds.values()) {
            boolean went = mailer.send(round.recipient, round.entries);
            told++;
            if (round.recipient.address() == null || round.recipient.address().isBlank()) {
                unaddressed++;
            }
            if (rehearsing) {
                // Nothing saved and nothing marked: a rehearsal that left notifications
                // behind would be answering the question by doing the thing.
                continue;
            }
            Notification notification = round.toNotification(now);
            notifications.save(notification);
            if (went) {
                notification.markEmailed(now);
            }
        }
        // Asked of the mailer rather than counted here: one person can be owed two messages,
        // and "how many emails" is a question about messages.
        int emailed = mailer.sent();
        int reported = expiring.size() - superseded;
        log.info("Expiry digest{}: {} certificate(s) expiring in the next {} day(s), {} already replaced, "
                        + "{} person(s) told, {} message(s) emailed",
                rehearsing ? " (dry run)" : "", expiring.size(), leadDays, superseded, told, emailed);
        String note = emailed > 0
                ? null
                : whyNothingWasBuilt(expiring.size(), superseded, reported, told, unaddressed, leadDays);
        if (rehearsing) {
            List<NotificationMailer.Rendered> built = mailer.rendered();
            return new DigestResult(reported, told, emailed, true, properties.getEmail().isEnabled(), note,
                    built.size() > DRY_RUN_PREVIEW_LIMIT ? built.subList(0, DRY_RUN_PREVIEW_LIMIT) : built);
        }
        return DigestResult.sent(reported, told, emailed, properties.getEmail().isEnabled(), note);
    }

    /**
     * Why a run built no messages, in a sentence somebody can act on.
     *
     * <p>A rehearsal that comes back empty is the one result that says nothing by itself.
     * "No messages" is the answer to half a dozen different questions - a window nothing
     * falls inside, a directory that has renewed everything, points of contact with no
     * address published - and which of them it is decides whether anybody has anything to
     * do. So the run says which.
     */
    private String whyNothingWasBuilt(
            int expiring, int superseded, int reported, int told, int unaddressed, int leadDays) {

        if (reported == 0 && superseded > 0) {
            return "All " + expiring + " certificate(s) expiring in the next " + leadDays
                    + " day(s) have already been published again, so nobody needs telling";
        }
        if (told == 0) {
            return reported + " certificate(s) are expiring, but none of them has anybody to tell: "
                    + "no point of contact resolves to a person or an address";
        }
        if (unaddressed == told) {
            return told + " person/people would be told on the page, but none of them publishes an "
                    + "address to write to";
        }
        if (unaddressed > 0) {
            return unaddressed + " of the " + told + " to be told publish no address to write to";
        }
        NotificationProperties.Email email = properties.getEmail();
        if (told >= email.getMaxPerRun()) {
            return "Stopped at the " + email.getMaxPerRun() + " message cap for one run "
                    + "(cert-alert.notifications.email.max-per-run)";
        }
        return "Nothing was built, and nothing here says why - the log will have it";
    }

    private DigestResult nothing(boolean dryRun, String note) {
        return DigestResult.nothing(dryRun, properties.getEmail().isEnabled(), note);
    }

    /**
     * The certificates an entry is still relying on, by id.
     *
     * <p>Two things have to be true of a certificate before anybody is written to about it,
     * and they answer different questions.
     *
     * <p>{@link DirectoryEntry#standingCertificates()} says what the entry's own state is
     * based on. It is what the tables show, so a server reading VALID because it publishes
     * a good certificate does not also generate mail about the one beside it; the page and
     * the message agree, which they would not if this asked a question of its own.
     *
     * <p>{@link Renewals} says whether a certificate has been <em>replaced</em> - the same
     * subject, published again and still live. That is what stops the round-up going on
     * about a certificate somebody has already dealt with, which is how a round-up teaches
     * people to ignore it. Note that it is not "the newest one wins": an entry holding two
     * certificates for different names holds both because it needs both.
     */
    private Set<Long> stillDependedOn(DirectoryEntry entry) {
        List<CachedCertificate> held = entry.getCertificates();
        Set<Long> ids = new HashSet<>();
        for (CachedCertificate certificate : entry.standingCertificates()) {
            if (certificate.getId() == null) {
                continue;
            }
            if (!Renewals.replaced(certificate, held)) {
                ids.add(certificate.getId());
            }
        }
        return ids;
    }

    /**
     * Every certificate of the credential this alert is about, by fingerprint.
     *
     * <p>Just the one for a server: its certificate signs and is encrypted to, so it is a
     * whole credential by itself. For a person it is the pair, which is the point - the two
     * halves are issued together, expire together and are renewed together, so being told
     * about them twice is being told the same thing twice.
     */
    private List<String> credentialFingerprintsOf(CertificateAlert alert) {
        String fingerprint = alert.certificateFingerprint();
        if (fingerprint == null) {
            return List.of();
        }
        if (alert.ownerType() != OwnerType.USER || alert.ownerId() == null) {
            return List.of(fingerprint);
        }
        List<CachedCertificate> held = certificates.findForUsers(List.of(alert.ownerId()));
        return Credential.group(held, credentialProperties.getPairWindow()).stream()
                .filter(credential -> credential.certificates().stream()
                        .anyMatch(certificate -> fingerprint.equals(certificate.getSha256Fingerprint())))
                .findFirst()
                .map(credential -> credential.certificates().stream()
                        .map(CachedCertificate::getSha256Fingerprint)
                        .filter(Objects::nonNull)
                        .toList())
                .orElse(List.of(fingerprint));
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

    /**
     * What one run of the digest did.
     *
     * @param certificates how many expiring certificates were reported, replaced ones aside
     * @param peopleTold how many recipients were written to
     * @param emailsSent how many messages went out - more than {@code peopleTold} where
     *     somebody is owed one message about their own certificates and another about the
     *     servers they look after
     * @param dryRun whether this was a rehearsal, in which case nothing was sent or saved
     * @param emailEnabled whether email is switched on - a rehearsal builds its messages
     *     either way, and this is what stops "3 would be sent" being read as "and they will"
     * @param note why no messages were built, where none were; null where some were
     * @param messages on a rehearsal, the messages as they would have gone out
     */
    public record DigestResult(
            int certificates,
            int peopleTold,
            int emailsSent,
            boolean dryRun,
            boolean emailEnabled,
            String note,
            List<NotificationMailer.Rendered> messages) {

        /** A run with nothing to do, which is still a run of one kind or the other. */
        static DigestResult nothing(boolean dryRun, boolean emailEnabled, String note) {
            return new DigestResult(0, 0, 0, dryRun, emailEnabled, note, List.of());
        }

        /** A run that sent: {@code emailsSent} is what went out, and nothing was rendered aside. */
        static DigestResult sent(
                int certificates, int peopleTold, int emailsSent, boolean emailEnabled, String note) {
            return new DigestResult(certificates, peopleTold, emailsSent, false, emailEnabled, note, List.of());
        }
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

        return fingerprint != null && alreadyTold(recipient, List.of(fingerprint), severity, kind, since);
    }

    /** The same question about every certificate of one credential: told about any is told. */
    private boolean alreadyTold(
            NotificationRecipients.Recipient recipient,
            List<String> fingerprints,
            Severity severity,
            NotificationKind kind,
            Instant since) {

        if (fingerprints.isEmpty()) {
            return false;
        }
        if (recipient.userId() != null) {
            return notifications.existsByRecipientUserIdAndCertificateFingerprintInAndSeverityAndKindAndCreatedAtAfter(
                    recipient.userId(), fingerprints, severity, kind, since);
        }
        return notifications.existsByRecipientAddressAndCertificateFingerprintInAndSeverityAndKindAndCreatedAtAfter(
                recipient.address(), fingerprints, severity, kind, since);
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
                Credential credential, AuditEvent.SubjectRef subject, OwnerType ownerType, Instant now) {

            ExpiryDigest.Entry entry = ExpiryDigest.Entry.of(credential, subject, ownerType, now);
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
            // One of them is named outright. Several on one entry name the entry; several
            // across the directory are counted by how many entries they are on. Repeating
            // the same number twice - "2 expiring across 2" - tells nobody anything.
            long named = entries.stream().map(ExpiryDigest.Entry::getOwnerDn).distinct().count();
            String where;
            if (entries.size() == 1) {
                where = "(" + entries.getFirst().getSummary() + ")";
            } else if (named == 1) {
                where = "for " + entries.getFirst().getOwnerName();
            } else {
                where = "across " + named + " directory entries";
            }
            String message = "%s %s".formatted(summary(), where);
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

        /**
         * What the page says. Counted in credentials, since that is what somebody has to
         * do something about: a person's pair is one of them, and two lines with two
         * serial numbers describing one renewal is the noise this avoids.
         */
        private String summary() {
            if (expired > 0 && expiring > 0) {
                return "%d credential(s) expired and %d expiring".formatted(expired, expiring);
            }
            return expired > 0
                    ? "%d credential(s) expired".formatted(expired)
                    : "%d credential(s) expiring".formatted(expiring);
        }

        private static String truncate(String message) {
            return message.length() <= 1000 ? message : message.substring(0, 997) + "...";
        }
    }
}

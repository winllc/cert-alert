package com.winllc.certalert.service;

import com.winllc.certalert.config.CredentialProperties;
import com.winllc.certalert.config.RevocationProperties;
import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.DirectoryEntry;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.RevocationStatus;
import com.winllc.certalert.repository.CachedCertificateRepository;
import com.winllc.certalert.revocation.RevocationChecker;
import jakarta.annotation.PreDestroy;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One page of the revocation check: a batch of entries, asked about and written back.
 *
 * <p>A bean of its own so each page is its own transaction. The check goes out over the
 * network - to a CRL distribution point, and the first entry through pays for the download
 * - and a single transaction spanning a hundred thousand entries would hold a database
 * connection open across every one of those fetches.
 *
 * <p>Entries rather than certificates, because which certificates are current is a question
 * about the entry: a person holds a signing certificate and an encryption certificate, and
 * the pair is what they are working with. Superseded certificates are left alone - an
 * authority revoking one that has already been replaced is not news.
 */
@Service
public class RevocationPageProcessor {

    private static final Logger log = LoggerFactory.getLogger(RevocationPageProcessor.class);

    private final CachedCertificateRepository certificates;
    private final RevocationChecker checker;
    private final NotificationService notifications;
    private final AuditService audit;
    private final CredentialProperties credentials;
    private final PublishedCertificates published;
    private final ExecutorService workers;

    public RevocationPageProcessor(
            CachedCertificateRepository certificates,
            RevocationChecker checker,
            NotificationService notifications,
            AuditService audit,
            CredentialProperties credentials,
            PublishedCertificates published,
            RevocationProperties properties) {
        this.certificates = certificates;
        this.checker = checker;
        this.notifications = notifications;
        this.audit = audit;
        this.credentials = credentials;
        this.published = published;
        this.workers = Executors.newFixedThreadPool(properties.getWorkers(), runnable -> {
            Thread thread = new Thread(runnable, "revocation-check");
            // Nothing here should hold the application open: a run in flight at shutdown
            // has a scheduled successor, and the cache is no worse for missing one.
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    void stopAsking() {
        workers.shutdownNow();
    }

    /** What one page did, and where the next one starts. */
    public record Page(int entries, int checked, int revoked, int unknown, long lastOwnerId) {

        static final Page EMPTY = new Page(0, 0, 0, 0, 0);
    }

    @Transactional
    public Page check(OwnerType type, long after, int size, Instant now) {
        List<Long> owners = type == OwnerType.USER
                ? certificates.findUserIdsAfter(after, PageRequest.of(0, size))
                : certificates.findServerIdsAfter(after, PageRequest.of(0, size));
        if (owners.isEmpty()) {
            return Page.EMPTY;
        }
        List<CachedCertificate> all = type == OwnerType.USER
                ? certificates.findForUsers(owners)
                : certificates.findForServers(owners);

        Map<Long, List<CachedCertificate>> byOwner = new LinkedHashMap<>();
        for (CachedCertificate certificate : all) {
            byOwner.computeIfAbsent(ownerIdOf(certificate, type), key -> new ArrayList<>()).add(certificate);
        }

        // Which certificates to ask about, and where to get the bytes for the ones that
        // need a responder - both decided here, on the thread that owns the transaction,
        // because both read the entity and one of them follows a lazy association.
        List<Question> questions = new ArrayList<>(byOwner.size());
        for (List<CachedCertificate> owned : byOwner.values()) {
            List<CachedCertificate> current =
                    CertificateIssuance.current(owned, credentials.getPairWindow(), now).current();
            questions.add(new Question(current, entryNeedingItsBytes(current, type)));
        }

        int checkedCount = 0;
        int revokedCount = 0;
        int unknownCount = 0;
        for (Answer answer : ask(questions, type, now)) {
            RevocationStatus before = answer.certificate.getRevocationStatus();
            RevocationChecker.Outcome outcome = answer.outcome;
            answer.certificate.recordRevocation(
                    outcome.status(),
                    outcome.method(),
                    outcome.revokedAt(),
                    outcome.reason(),
                    outcome.detail(),
                    now);
            checkedCount++;
            if (outcome.status() == RevocationStatus.UNKNOWN) {
                unknownCount++;
            }
            if (outcome.isRevoked()) {
                revokedCount++;
                // Only on the transition: a certificate that was revoked last night is
                // still revoked tonight, and telling everybody again every night is how
                // people learn to ignore it.
                if (before != RevocationStatus.REVOKED) {
                    announce(answer.certificate, type, now);
                }
            }
        }
        return new Page(owners.size(), checkedCount, revokedCount, unknownCount, owners.getLast());
    }

    /** One entry's certificates, and the entry itself where its bytes will be needed. */
    private record Question(List<CachedCertificate> certificates, DirectoryEntry needsBytes) {}

    /** What an authority said about one certificate. */
    private record Answer(CachedCertificate certificate, RevocationChecker.Outcome outcome) {}

    /**
     * Asks the authorities, several at a time.
     *
     * <p>Every question here is a network round trip, and OCSP is one of them per
     * certificate: asked one after another at a few hundred milliseconds each, a directory
     * of any size does not finish overnight, and against a responder that has stopped
     * answering each one waits out the timeout before the next begins. Widthways it is the
     * same work in a fraction of the time, and the width is
     * {@code cert-alert.revocation.workers}.
     *
     * <p>Only the asking happens off this thread. Everything that touches the database -
     * recording what came back, telling people, writing the audit trail - happens on the
     * thread that owns the transaction, on the answers, in order. What the workers read of
     * an entity is the columns that came with it: the serial, the endpoints, the issuer.
     * Nothing they touch is lazy, which is why the entry whose bytes are wanted is resolved
     * before they start.
     */
    private List<Answer> ask(List<Question> questions, OwnerType type, Instant now) {
        List<Future<List<Answer>>> pending = new ArrayList<>(questions.size());
        for (Question question : questions) {
            pending.add(workers.submit(() -> answer(question, type, now)));
        }

        List<Answer> answers = new ArrayList<>();
        for (Future<List<Answer>> future : pending) {
            try {
                answers.addAll(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while checking revocation", e);
            } catch (ExecutionException e) {
                // One entry that could not be asked about is not a reason to abandon the
                // page; the rest of it is still worth checking.
                log.warn("Could not check one entry's certificates: {}", e.getCause().toString());
            }
        }
        return answers;
    }

    private List<Answer> answer(Question question, OwnerType type, Instant now) {
        Map<String, X509Certificate> leaves =
                question.needsBytes == null ? Map.of() : published.of(question.needsBytes, type);
        List<Answer> answers = new ArrayList<>(question.certificates.size());
        for (CachedCertificate certificate : question.certificates) {
            answers.add(new Answer(
                    certificate,
                    checker.check(certificate, leaves.get(certificate.getSha256Fingerprint()), now)));
        }
        return answers;
    }

    /**
     * The certificates of this entry as the directory publishes them, or nothing.
     *
     * <p>Resolved on the thread that owns the transaction, because it follows a lazy
     * association and a worker thread has no business doing that.
     *
     * <p>Asked for only where a responder is the only way to answer: no list to fetch,
     * a responder address from the certificate or the configuration, and an issuer
     * certificate to name the certificate by. That is the check the scheduled job could
     * not make before - a list needs only the cached serial number, and OCSP needs the
     * certificate itself, so a job working from the cache alone could never ask a
     * responder anything - and it stays off the path of every deployment whose
     * certificates name their distribution points.
     */
    private DirectoryEntry entryNeedingItsBytes(List<CachedCertificate> current, OwnerType type) {
        for (CachedCertificate certificate : current) {
            if (checker.onlyAResponderCanAnswer(certificate)) {
                return type == OwnerType.USER ? certificate.getUser() : certificate.getServer();
            }
        }
        return null;
    }

    /** Records it against the entry and tells whoever is responsible for it. */
    private void announce(CachedCertificate certificate, OwnerType type, Instant now) {
        AuditEvent.SubjectRef subject = subjectOf(certificate, type);
        log.warn("Certificate {} (serial {}) for '{}' has been revoked: {}",
                certificate.getSha256Fingerprint(),
                certificate.getSerialNumber(),
                subject.dn(),
                certificate.getRevocationDetail());

        audit.record(AuditEvent.about(
                        subject,
                        AuditAction.CERTIFICATE_REVOKED,
                        "Revoked%s%s, according to %s".formatted(
                                certificate.getRevokedAt() == null ? "" : " on " + certificate.getRevokedAt(),
                                certificate.getRevocationReason() == null
                                        ? ""
                                        : " (" + certificate.getRevocationReason() + ")",
                                certificate.getRevocationDetail()),
                        now)
                .by(AuditActors.REVOCATION)
                .forCertificate(certificate.getSha256Fingerprint()));

        notifications.notifyRevoked(certificate, subject, type);
    }

    private static Long ownerIdOf(CachedCertificate certificate, OwnerType type) {
        if (type == OwnerType.USER) {
            DirectoryUser user = certificate.getUser();
            return user == null ? null : user.getId();
        }
        DirectoryServer server = certificate.getServer();
        return server == null ? null : server.getId();
    }

    private static AuditEvent.SubjectRef subjectOf(CachedCertificate certificate, OwnerType type) {
        if (type == OwnerType.USER) {
            return AuditEvent.SubjectRef.of(certificate.getUser());
        }
        return AuditEvent.SubjectRef.of(certificate.getServer());
    }
}

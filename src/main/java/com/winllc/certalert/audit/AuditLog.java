package com.winllc.certalert.audit;

import com.enssolutions.license.LicenseManager;
import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.license.LicensingConfiguration;
import com.winllc.certalert.service.AuditActors;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one writer of the audit trail: appends {@code ens-audit/1} events to the store, in the
 * database, and hands them to the outputs.
 *
 * <p><b>Synchronous, and in the caller's transaction.</b> An event joins whatever transaction
 * caused it, so the change and its record commit together or not at all. Where there is no
 * transaction it gets one of its own. The events are held until the transaction is about to
 * commit and written then, in one go.
 *
 * <p><b>Serialised by a row lock.</b> At that moment the writer locks the single
 * {@code audit_chain_head} row ({@code SELECT ... FOR UPDATE}), numbers its events from the
 * head's seq, chains each to the previous hash, inserts them and moves the head - and the
 * commit releases the lock. Holding it only from just before the commit keeps it short,
 * whatever the transaction did first; and because it is a database lock, two instances
 * sharing one database cannot interleave either. Seq is therefore gapless and in commit
 * order, and the chain never forks.
 *
 * <p><b>Failure.</b> {@link #record} is for security actions - a sign-in, a role, settings, the
 * license: if the event cannot be written the transaction fails and the action with it, so
 * nothing changes unaudited. {@link #recordQuietly} is for system and job events: it writes
 * in a transaction of its own and logs, rather than throws, when it cannot.
 *
 * <p><b>History.</b> Rows written before the format existed have no seq. The first append
 * after startup (and startup itself, see {@link AuditStartup}) numbers and chains them,
 * oldest first, ahead of anything new; each keeps its time and carries
 * {@code details.migrated=true}.
 */
@Service
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    private static final int MIGRATION_CHUNK = 500;

    @PersistenceContext
    private EntityManager entityManager;

    private final TransactionTemplate joining;
    private final TransactionTemplate independent;
    private final AuditSinks sinks;
    private final LicenseManager licenses;
    private final ObjectProvider<BuildProperties> build;
    private final ObjectProvider<AuditDeliveryService> delivery;
    private final Clock clock;

    private volatile AuditFormat.Origin origin;
    private volatile boolean historyChained;

    public AuditLog(
            PlatformTransactionManager transactions,
            AuditSinks sinks,
            LicenseManager licenses,
            ObjectProvider<BuildProperties> build,
            ObjectProvider<AuditDeliveryService> delivery,
            Clock clock) {
        this.joining = new TransactionTemplate(transactions);
        this.independent = new TransactionTemplate(transactions);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.sinks = sinks;
        this.licenses = licenses;
        this.build = build;
        this.delivery = delivery;
        this.clock = clock;
    }

    /**
     * Records a security event: in the caller's transaction, failing it if the event cannot
     * be written.
     */
    public void record(AuditRecord event) {
        recordAll(List.of(event));
    }

    public void recordAll(Collection<AuditRecord> events) {
        if (events.isEmpty()) {
            return;
        }
        List<AuditRecord> prepared = prepare(events);
        TransactionTemplate template = TransactionSynchronizationManager.isActualTransactionActive()
                        && TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ? independent
                : joining;
        template.executeWithoutResult(status -> pending().add(prepared));
    }

    /**
     * Records a system or job event in a transaction of its own. Never throws: a failure is
     * logged, and the work it describes carries on.
     *
     * @return whether it was written
     */
    public boolean recordQuietly(AuditRecord event) {
        try {
            List<AuditRecord> prepared = prepare(List.of(event));
            independent.executeWithoutResult(status -> pending().add(prepared));
            return true;
        } catch (RuntimeException e) {
            log.error("Could not record the audit event {}: {}", event.action(), e.toString(), e);
            return false;
        }
    }

    /**
     * Numbers and chains whatever history predates the format. Run at startup; harmless to
     * run again.
     */
    public void chainHistory() {
        independent.executeWithoutResult(status -> pending().touch());
    }

    /** The fields every event from this installation carries. */
    public AuditFormat.Origin origin() {
        AuditFormat.Origin current = origin;
        if (current == null) {
            String version = build.getIfAvailable() == null ? null : build.getIfAvailable().getVersion();
            String installation;
            try {
                installation = licenses.installationId();
            } catch (RuntimeException e) {
                installation = null;
            }
            current = new AuditFormat.Origin(
                    LicensingConfiguration.PRODUCT,
                    version == null || version.isBlank() ? "dev" : version,
                    hostname(),
                    installation);
            origin = current;
        }
        return current;
    }

    private List<AuditRecord> prepare(Collection<AuditRecord> events) {
        Instant now = Instant.now(clock);
        List<AuditRecord> prepared = new ArrayList<>(events.size());
        for (AuditRecord event : events) {
            AuditContext.capture(event);
            if (event.time() == null) {
                event.time(now);
            }
            prepared.add(event);
        }
        return prepared;
    }

    /** This transaction's pending events, registered with it on first use. */
    private Pending pending() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof Pending pending && pending.owner == this) {
                return pending;
            }
        }
        Pending pending = new Pending(this);
        TransactionSynchronizationManager.registerSynchronization(pending);
        return pending;
    }

    /** Appends under the chain-head lock. Runs just before the transaction commits. */
    private Written write(List<AuditRecord> events, boolean forceHistory) {
        AuditChainHead head = entityManager.find(AuditChainHead.class, AuditChainHead.ID);
        if (head == null) {
            throw new IllegalStateException("The audit chain head row is missing");
        }
        entityManager.refresh(head, LockModeType.PESSIMISTIC_WRITE);

        List<String> lines = new ArrayList<>();
        boolean chainedHistory = false;
        if (!historyChained || forceHistory) {
            lines.addAll(chainHistory(head));
            chainedHistory = true;
        }
        AuditFormat.Origin from = origin();
        for (AuditRecord event : events) {
            AuditEvent row = event.entity() != null ? event.entity() : AuditEvent.event(event.time(), null);
            lines.add(append(head, event, row, from));
            entityManager.persist(row);
        }
        entityManager.flush();
        return new Written(lines, head.getLastSeq(), chainedHistory);
    }

    private String append(AuditChainHead head, AuditRecord event, AuditEvent row, AuditFormat.Origin from) {
        long seq = head.getLastSeq() + 1;
        String body = AuditFormat.body(seq, event, from, head.getLastHash());
        String hash = AuditFormat.hash(head.getLastHash(), body);
        String line = AuditFormat.line(body, hash);
        AuditRecord.Actor actor = event.actor();
        AuditRecord.Target target = event.target();
        AuditRecord.Source source = event.source();
        row.chain(
                seq,
                event.id(),
                event.category().id(),
                event.action(),
                event.outcome().id(),
                event.severity().id(),
                actor.type().id(),
                actor.id(),
                target == null ? null : target.type(),
                target == null ? null : target.id(),
                source == null ? null : source.ip(),
                hash,
                line);
        head.advance(seq, hash);
        return line;
    }

    /** Numbers the rows from before the format, oldest first. */
    private List<String> chainHistory(AuditChainHead head) {
        List<Long> ids = entityManager
                .createQuery(
                        "select e.id from AuditEvent e where e.seq is null order by e.occurredAt, e.id", Long.class)
                .getResultList();
        if (ids.isEmpty()) {
            return List.of();
        }
        log.info("Chaining {} audit record(s) from before ens-audit/1 into the audit trail", ids.size());
        AuditFormat.Origin from = origin();
        List<String> lines = new ArrayList<>(ids.size());
        for (int start = 0; start < ids.size(); start += MIGRATION_CHUNK) {
            List<Long> chunk = ids.subList(start, Math.min(ids.size(), start + MIGRATION_CHUNK));
            List<AuditEvent> rows = new ArrayList<>(entityManager
                    .createQuery("select e from AuditEvent e where e.id in :ids", AuditEvent.class)
                    .setParameter("ids", chunk)
                    .getResultList());
            rows.sort(Comparator.comparing(AuditEvent::getOccurredAt).thenComparing(AuditEvent::getId));
            for (AuditEvent row : rows) {
                lines.add(append(head, fromEntry(row, true), row, from));
            }
            entityManager.flush();
            rows.forEach(entityManager::detach);
        }
        return lines;
    }

    /** An entry change as it is recorded now, by whoever is acting. */
    public static AuditRecord entry(AuditEvent entry) {
        return fromEntry(entry, false);
    }

    /**
     * An entry change - the records this application has always kept about the directory -
     * as an {@code ens-audit/1} event. Its kind maps onto a dotted action id and a category
     * ({@link AuditAction#id()}); the entry is the target, named by its DN.
     */
    static AuditRecord fromEntry(AuditEvent entry, boolean migrated) {
        AuditAction action = entry.getAction();
        AuditRecord event = AuditRecord.of(AuditRecord.Category.of(action.category()), action.id())
                .outcome(AuditRecord.Outcome.of(action.outcome()))
                .severity(AuditRecord.Severity.of(action.severity()))
                .time(entry.getOccurredAt())
                .entity(entry);
        if (entry.getSubjectType() != null) {
            event.target(entry.getSubjectType().name().toLowerCase(Locale.ROOT), entry.getSubjectDn(),
                    entry.getSubjectName());
        }
        event.detail("summary", entry.getSummary());
        if (entry.getSubjectId() != null) {
            event.detail("entryId", entry.getSubjectId());
        }
        if (entry.getCertificateFingerprint() != null) {
            event.detail("certificateFingerprint", entry.getCertificateFingerprint());
        }
        if (entry.getChannel() != null) {
            event.detail("channel", entry.getChannel());
        }
        if (entry.getTarget() != null) {
            event.detail("to", entry.getTarget());
        }
        if (event.outcome() != AuditRecord.Outcome.SUCCESS) {
            event.reason(entry.getSummary());
        }
        if (migrated) {
            event.detail("migrated", true);
            event.actor(actorNamed(entry.getActor(), false));
            event.markContextCaptured();
        } else {
            event.actor(actorNamed(entry.getActor(), true));
        }
        return event;
    }

    /** One of the jobs, or a person - in full, where it is whoever is signed in. */
    static AuditRecord.Actor actorNamed(String actor, boolean live) {
        if (actor == null || AuditActors.isJob(actor)) {
            return AuditRecord.Actor.system(actor);
        }
        if (live) {
            AuditRecord.Actor current = AuditContext.currentActor();
            if (actor.equals(current.id())) {
                return current;
            }
        }
        return new AuditRecord.Actor(AuditRecord.ActorType.USER, actor, null, List.of());
    }

    private void committed(Written written) {
        if (written.chainedHistory()) {
            historyChained = true;
        }
        sinks.emit(written.lines(), written.lastSeq());
        AuditDeliveryService forwarders = delivery.getIfAvailable();
        if (forwarders != null && !written.lines().isEmpty()) {
            forwarders.wake();
        }
    }

    private static String hostname() {
        String env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) {
            return env;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private record Written(List<String> lines, long lastSeq, boolean chainedHistory) {}

    /** The events a transaction has recorded, written as it commits. */
    private static final class Pending implements TransactionSynchronization {

        private final AuditLog owner;
        private final List<AuditRecord> events = new ArrayList<>();
        private boolean touched;
        private Written written;

        Pending(AuditLog owner) {
            this.owner = owner;
        }

        void add(List<AuditRecord> more) {
            events.addAll(more);
        }

        void touch() {
            touched = true;
        }

        @Override
        public void beforeCommit(boolean readOnly) {
            if (events.isEmpty() && !touched) {
                return;
            }
            written = owner.write(List.copyOf(events), touched);
            events.clear();
        }

        @Override
        public void afterCommit() {
            if (written != null) {
                owner.committed(written);
            }
        }

        @Override
        public int getOrder() {
            // After anything else that writes before the commit, so the lock is held for
            // as short a time as possible.
            return LOWEST_PRECEDENCE;
        }
    }
}

package com.winllc.certalert.audit;

import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.repository.AuditEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reads the audit store: by seq for delivery and verification, by filter for the audit page. */
@Component
public class AuditStore {

    @PersistenceContext
    private EntityManager entityManager;

    private final AuditEventRepository repository;

    public AuditStore(AuditEventRepository repository) {
        this.repository = repository;
    }

    /** The last seq handed out. */
    @Transactional(readOnly = true)
    public long headSeq() {
        AuditChainHead head = entityManager.find(AuditChainHead.class, AuditChainHead.ID);
        return head == null ? 0 : head.getLastSeq();
    }

    /** The chain head: its seq and hash, and the last seq retention pruned and that event's hash. */
    public record ChainState(long lastSeq, String lastHash, long prunedThroughSeq, String prunedThroughHash) {}

    /** The chain head as it is now, read afresh. */
    @Transactional(readOnly = true)
    public ChainState chainState() {
        List<Object[]> rows = entityManager
                .createQuery("select h.lastSeq, h.lastHash, h.prunedThroughSeq, h.prunedThroughHash "
                        + "from AuditChainHead h where h.id = :id", Object[].class)
                .setParameter("id", AuditChainHead.ID)
                .getResultList();
        if (rows.isEmpty()) {
            return new ChainState(0, AuditFormat.GENESIS, 0, null);
        }
        Object[] row = rows.getFirst();
        return new ChainState(((Number) row[0]).longValue(), (String) row[1], ((Number) row[2]).longValue(),
                (String) row[3]);
    }

    /** Events after this seq, ascending, at most {@code limit}. */
    @Transactional(readOnly = true)
    public List<StoredEvent> after(long seq, int limit) {
        return entityManager
                .createQuery(
                        "select new com.winllc.certalert.audit.StoredEvent(e.seq, e.occurredAt, e.eventAction, "
                                + "e.severity, e.line) from AuditEvent e where e.seq > :seq order by e.seq",
                        StoredEvent.class)
                .setParameter("seq", seq)
                .setMaxResults(limit)
                .getResultList();
    }

    @Transactional(readOnly = true)
    public Optional<AuditEvent> bySeq(long seq) {
        return entityManager
                .createQuery("select e from AuditEvent e where e.seq = :seq", AuditEvent.class)
                .setParameter("seq", seq)
                .getResultStream()
                .findFirst();
    }

    /** Newest first. */
    @Transactional(readOnly = true)
    public Page<AuditEvent> search(AuditQuery query, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), Sort.by(Sort.Direction.DESC, "seq"));
        return repository.findAll(query.toSpecification(), pageable);
    }

    /**
     * Every match, newest first, up to {@code limit}, a page at a time so an export does not
     * hold the whole trail at once.
     *
     * @return how many were handed over
     */
    @Transactional(readOnly = true)
    public int forEach(AuditQuery query, int limit, Consumer<AuditEvent> consumer) {
        int handed = 0;
        int page = 0;
        while (handed < limit) {
            Page<AuditEvent> batch = repository.findAll(
                    query.toSpecification(), PageRequest.of(page++, 1000, Sort.by(Sort.Direction.DESC, "seq")));
            for (AuditEvent event : batch.getContent()) {
                if (handed == limit) {
                    break;
                }
                consumer.accept(event);
                handed++;
            }
            batch.getContent().forEach(entityManager::detach);
            if (!batch.hasNext()) {
                break;
            }
        }
        return handed;
    }

    /** Distinct action ids, for the audit page's filter. */
    @Transactional(readOnly = true)
    public List<String> actions() {
        return entityManager
                .createQuery("select distinct e.eventAction from AuditEvent e where e.eventAction is not null "
                        + "order by e.eventAction", String.class)
                .getResultList();
    }

    /** Lowest retained seq, or null when the store is empty. */
    @Transactional(readOnly = true)
    public Long firstSeq() {
        return entityManager.createQuery("select min(e.seq) from AuditEvent e", Long.class).getSingleResult();
    }

    @Transactional(readOnly = true)
    public Instant oldest() {
        return entityManager.createQuery("select min(e.occurredAt) from AuditEvent e where e.seq is not null",
                Instant.class).getSingleResult();
    }
}

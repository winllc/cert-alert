package com.winllc.certalert.audit;

import com.winllc.certalert.config.AuditProperties;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pruning: the only thing that ever deletes from the audit trail.
 *
 * <p>Off unless configured: {@code ENS_AUDIT_RETENTION_DAYS} (default 0, keep forever) or,
 * where that is 0, the older {@code cert-alert.audit.retention.enabled} and
 * {@code .after}, which keep working. Removes events older than the window that every
 * enabled forwarding output has already delivered, never the newest event (the chain's
 * head), and records {@code audit.pruned} with the count and the seq range removed.
 */
@Component
public class AuditRetention {

    private static final Logger log = LoggerFactory.getLogger(AuditRetention.class);

    @PersistenceContext
    private EntityManager entityManager;

    private final EnsAuditProperties properties;
    private final AuditProperties legacy;
    private final AuditStore store;
    private final AuditDeliveryService delivery;
    private final AuditLog audit;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public AuditRetention(
            EnsAuditProperties properties,
            AuditProperties legacy,
            AuditStore store,
            AuditDeliveryService delivery,
            AuditLog audit,
            PlatformTransactionManager transactions,
            Clock clock) {
        this.properties = properties;
        this.legacy = legacy;
        this.store = store;
        this.delivery = delivery;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /** The retention window in days; 0 keeps everything. */
    public int retentionDays() {
        if (properties.getRetentionDays() > 0) {
            return properties.getRetentionDays();
        }
        AuditProperties.Retention old = legacy.getRetention();
        if (old.isEnabled() && old.getAfter() != null && !old.getAfter().isNegative()) {
            return (int) Math.max(1, old.getAfter().toDays());
        }
        return 0;
    }

    /** Where the setting came from, for the audit page. */
    public String describe() {
        int days = retentionDays();
        if (days == 0) {
            return "Kept forever (ENS_AUDIT_RETENTION_DAYS=0)";
        }
        String source = properties.getRetentionDays() > 0
                ? "ENS_AUDIT_RETENTION_DAYS"
                : "cert-alert.audit.retention.after";
        return "Events older than " + days + " day(s) are pruned daily once every output has delivered them ("
                + source + ")";
    }

    /** @return how many events were removed */
    public int prune() {
        int days = retentionDays();
        if (days <= 0) {
            return 0;
        }
        Instant cutoff = Instant.now(clock).minus(Duration.ofDays(days));
        long head = store.headSeq();
        long delivered = delivery.deliveredEverywhere(head);
        // Never the newest event: the head of the chain has to survive.
        long ceiling = Math.min(delivered, head - 1);
        if (ceiling <= 0) {
            return 0;
        }
        Range range = transaction.execute(status -> {
            Object[] bounds = (Object[]) entityManager.createQuery(
                            "select min(e.seq), max(e.seq), count(e) from AuditEvent e "
                                    + "where e.seq <= :ceiling and e.occurredAt < :cutoff")
                    .setParameter("ceiling", ceiling)
                    .setParameter("cutoff", cutoff)
                    .getSingleResult();
            if (bounds[0] == null) {
                return null;
            }
            long from = ((Number) bounds[0]).longValue();
            long to = ((Number) bounds[1]).longValue();
            // Only an unbroken run from the oldest retained event, so the trail is always a
            // contiguous stretch of the chain with one anchor.
            Long first = store.firstSeq();
            long upTo = to;
            Long gap = entityManager.createQuery(
                            "select min(e.seq) from AuditEvent e where e.seq <= :to and e.occurredAt >= :cutoff",
                            Long.class)
                    .setParameter("to", to)
                    .setParameter("cutoff", cutoff)
                    .getSingleResult();
            if (gap != null) {
                upTo = gap - 1;
            }
            if (first == null || upTo < first) {
                return null;
            }
            int removed = entityManager.createQuery("delete from AuditEvent e where e.seq <= :upTo")
                    .setParameter("upTo", upTo)
                    .executeUpdate();
            return new Range(first, upTo, removed);
        });
        if (range == null || range.removed() == 0) {
            return 0;
        }
        log.info("Pruned {} audit event(s), seq {} to {}, older than {} day(s)", range.removed(), range.from(),
                range.to(), days);
        audit.recordQuietly(AuditRecord.of(AuditRecord.Category.AUDIT, "audit.pruned")
                .actor(AuditRecord.Actor.system("audit retention"))
                .severity(AuditRecord.Severity.NOTICE)
                .detail("count", range.removed())
                .detail("fromSeq", range.from())
                .detail("toSeq", range.to())
                .detail("retentionDays", days)
                .detail("cutoff", AuditFormat.time(cutoff)));
        return range.removed();
    }

    private record Range(long from, long to, int removed) {}
}

package com.winllc.certalert.inventory;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.RevocationStatus;
import com.winllc.certalert.license.Licensing;
import com.winllc.certalert.repository.CachedCertificateRepository;
import com.winllc.certalert.service.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The certificate inventory: every certificate the directory publishes, with who holds it,
 * narrowed by a {@link CertificateFilter}. Paged for the API and the page, streamed for an
 * export, counted for the expiry summary.
 */
@Service
public class CertificateInventory {

    /** Columns a list may be ordered by; anything else is refused rather than passed to the query. */
    public static final Set<String> SORTABLE =
            Set.of("notAfter", "notBefore", "subjectDn", "issuerDn", "keySize", "keyAlgorithm", "hashAlgorithm", "status", "id");

    /** Rows read before the persistence context is cleared, so an export's memory stays flat. */
    private static final int CLEAR_EVERY = 500;

    private final CachedCertificateRepository certificates;
    private final EntityManager entityManager;
    private final Licensing licensing;
    private final Clock clock;

    public CertificateInventory(
            CachedCertificateRepository certificates, EntityManager entityManager, Licensing licensing, Clock clock) {
        this.certificates = certificates;
        this.entityManager = entityManager;
        this.licensing = licensing;
        this.clock = clock;
    }

    /** Expiry first, by default: what an inventory is mostly read for. */
    public static Sort sort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Order.asc("notAfter"), Sort.Order.asc("id"));
        }
        String[] parts = sort.split(",");
        String property = parts[0].trim();
        if (!SORTABLE.contains(property)) {
            throw new IllegalArgumentException("Certificates can be sorted by " + String.join(", ", SORTABLE.stream().sorted().toList()));
        }
        boolean descending = parts.length > 1 && parts[1].trim().toLowerCase(Locale.ROOT).startsWith("desc");
        Sort.Order order = descending ? Sort.Order.desc(property) : Sort.Order.asc(property);
        return Sort.by(order, Sort.Order.asc("id"));
    }

    @Transactional(readOnly = true)
    public Page<CertificateInventoryRow> page(CertificateFilter filter, int page, int size, Sort sort) {
        Instant now = Instant.now(clock);
        boolean risks = risksShown();
        return certificates.findAll(specification(filter, now, true), PageRequest.of(page, size, sort))
                .map(certificate -> CertificateInventoryRow.of(certificate, now, risks));
    }

    @Transactional(readOnly = true)
    public long count(CertificateFilter filter) {
        return certificates.count(specification(filter, Instant.now(clock), false));
    }

    @Transactional(readOnly = true)
    public CertificateInventoryRow find(Long id) {
        CachedCertificate certificate = certificates.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No certificate " + id));
        return CertificateInventoryRow.of(certificate, Instant.now(clock), risksShown());
    }

    /**
     * Every matching certificate, in order, one at a time, at most {@code limit} of them. Read
     * through a database cursor and handed on as it is read, so an export of a hundred
     * thousand rows is never a hundred thousand rows in memory. A negative limit is none.
     */
    @Transactional(readOnly = true)
    public long forEach(CertificateFilter filter, Sort sort, long limit, Consumer<CertificateInventoryRow> row) {
        if (limit == 0) {
            return 0;
        }
        Instant now = Instant.now(clock);
        boolean risks = risksShown();
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<CachedCertificate> query = builder.createQuery(CachedCertificate.class);
        Root<CachedCertificate> root = query.from(CachedCertificate.class);
        Predicate predicate = specification(filter, now, true).toPredicate(root, query, builder);
        if (predicate != null) {
            query.where(predicate);
        }
        List<Order> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            orders.add(order.isAscending() ? builder.asc(root.get(order.getProperty())) : builder.desc(root.get(order.getProperty())));
        }
        query.select(root).orderBy(orders);
        TypedQuery<CachedCertificate> typed = entityManager.createQuery(query)
                .setHint("org.hibernate.fetchSize", CLEAR_EVERY)
                .setHint("org.hibernate.readOnly", true);
        if (limit > 0 && limit < Integer.MAX_VALUE) {
            typed.setMaxResults((int) limit);
        }
        long written = 0;
        try (Stream<CachedCertificate> stream = typed.getResultStream()) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                row.accept(CertificateInventoryRow.of(iterator.next(), now, risks));
                if (++written % CLEAR_EVERY == 0) {
                    entityManager.clear();
                }
            }
        }
        return written;
    }

    /** How the inventory stands: by state, by how soon, by owner. */
    public record ExpirySummary(
            Instant asOf,
            long total,
            Map<String, Long> byStatus,
            Map<String, Long> expiringWithinDays,
            long revoked,
            Map<String, Long> byOwnerType) {}

    @Transactional(readOnly = true)
    public ExpirySummary summary(CertificateFilter filter) {
        Instant now = Instant.now(clock);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (CertificateStatus status : List.of(CertificateStatus.VALID, CertificateStatus.EXPIRING_SOON, CertificateStatus.EXPIRED)) {
            byStatus.put(status.name(), countWith(filter, now, (root, query, builder) -> builder.equal(root.get("status"), status)));
        }
        Map<String, Long> within = new LinkedHashMap<>();
        for (int days : new int[] {7, 30, 60, 90}) {
            Instant until = now.plus(days, ChronoUnit.DAYS);
            within.put(String.valueOf(days), countWith(filter, now,
                    (root, query, builder) -> builder.between(root.get("notAfter"), now, until)));
        }
        long revoked = countWith(filter, now,
                (root, query, builder) -> builder.equal(root.get("revocationStatus"), RevocationStatus.REVOKED));
        Map<String, Long> byOwner = new LinkedHashMap<>();
        byOwner.put(OwnerType.USER.name(), countWith(filter, now, (root, query, builder) -> builder.isNotNull(root.get("user"))));
        byOwner.put(OwnerType.SERVER.name(), countWith(filter, now, (root, query, builder) -> builder.isNotNull(root.get("server"))));
        return new ExpirySummary(now, certificates.count(specification(filter, now, false)), byStatus, within, revoked, byOwner);
    }

    private long countWith(CertificateFilter filter, Instant now, Specification<CachedCertificate> extra) {
        return certificates.count(specification(filter, now, false).and(extra));
    }

    private Specification<CachedCertificate> specification(CertificateFilter filter, Instant now, boolean fetchOwners) {
        return CertificateSpecifications.of(
                filter == null ? CertificateFilter.NONE : filter, now, risksShown(), licensing.shows("projects"), fetchOwners);
    }

    private boolean risksShown() {
        return licensing.shows("risky-names");
    }
}

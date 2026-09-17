package com.winllc.certalert.service;

import com.winllc.certalert.config.AuditProperties;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.repository.AuditEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes and reads the audit trail.
 *
 * <p>Records join whatever transaction produced them, rather than being written on the way
 * out or from another thread. An audit record that survives a rolled-back change is a lie,
 * and a lie in an audit trail is worse than a gap.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repository;
    private final AuditProperties properties;
    private final Clock clock;

    public AuditService(AuditEventRepository repository, AuditProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Writes one record. Joins the caller's transaction, which is the point: the record and
     * the change it describes commit together or not at all.
     */
    @Transactional
    public void record(AuditEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        repository.save(event);
    }

    /**
     * Writes a batch of records in one go, which is how a sweep writes: a page of entries
     * produces its records together rather than one round trip per change.
     */
    @Transactional
    public void recordAll(Collection<AuditEvent> events) {
        if (!properties.isEnabled() || events.isEmpty()) {
            return;
        }
        repository.saveAll(events);
    }

    /** One entry's history, newest first. */
    @Transactional(readOnly = true)
    public Page<AuditEvent> history(OwnerType subjectType, Long subjectId, Pageable pageable) {
        return repository.findBySubjectTypeAndSubjectIdOrderByOccurredAtDescIdDesc(subjectType, subjectId, pageable);
    }

    /**
     * Removes records older than the configured window. Does nothing unless retention has
     * been turned on deliberately.
     */
    @Transactional
    public int trim() {
        AuditProperties.Retention retention = properties.getRetention();
        if (!retention.isEnabled()) {
            return 0;
        }
        Instant cutoff = Instant.now(clock).minus(retention.getAfter());
        int removed = repository.deleteByOccurredAtBefore(cutoff);
        if (removed > 0) {
            log.info("Trimmed {} audit record(s) older than {}", removed, cutoff);
        }
        return removed;
    }
}

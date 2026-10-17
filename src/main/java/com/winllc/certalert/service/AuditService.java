package com.winllc.certalert.service;

import com.winllc.certalert.audit.AuditLog;
import com.winllc.certalert.audit.AuditRetention;
import com.winllc.certalert.config.AuditProperties;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.repository.AuditEventRepository;
import java.util.Collection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes and reads the history of directory entries - what happened to an entry, and who
 * did it.
 *
 * <p>Every record is an {@code ens-audit/1} event too: it goes through {@link AuditLog},
 * which numbers and chains it with everything else the application audits and hands it to
 * the outputs. Records join whatever transaction produced them, rather than being written on
 * the way out or from another thread. An audit record that survives a rolled-back change is
 * a lie, and a lie in an audit trail is worse than a gap.
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;
    private final AuditProperties properties;
    private final AuditLog auditLog;
    private final AuditRetention retention;

    public AuditService(
            AuditEventRepository repository,
            AuditProperties properties,
            AuditLog auditLog,
            AuditRetention retention) {
        this.repository = repository;
        this.properties = properties;
        this.auditLog = auditLog;
        this.retention = retention;
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
        auditLog.record(AuditLog.entry(event));
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
        auditLog.recordAll(events.stream().map(AuditLog::entry).toList());
    }

    /** One entry's history, newest first. */
    @Transactional(readOnly = true)
    public Page<AuditEvent> history(OwnerType subjectType, Long subjectId, Pageable pageable) {
        return repository.findBySubjectTypeAndSubjectIdOrderByOccurredAtDescIdDesc(subjectType, subjectId, pageable);
    }

    /**
     * Prunes events older than the retention window - {@code ENS_AUDIT_RETENTION_DAYS}, or
     * {@code cert-alert.audit.retention.*} where that is unset. Does nothing unless retention
     * has been turned on deliberately.
     */
    public int trim() {
        return retention.prune();
    }
}

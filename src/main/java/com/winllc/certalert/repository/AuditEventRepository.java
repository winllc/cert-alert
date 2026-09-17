package com.winllc.certalert.repository;

import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.OwnerType;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.datatables.repository.DataTablesRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * A DataTables repository, because the audit table on a details page is a search table like
 * the other two: the same paging, ordering and search, and the same request shape.
 */
public interface AuditEventRepository extends DataTablesRepository<AuditEvent, Long> {

    /**
     * One entry's history, newest first. The id breaks ties: a sweep writes several records
     * with the same instant, and a page boundary that falls between two of them has to land
     * in the same place every time or paging repeats and skips rows.
     */
    Page<AuditEvent> findBySubjectTypeAndSubjectIdOrderByOccurredAtDescIdDesc(
            OwnerType subjectType, Long subjectId, Pageable pageable);

    long countBySubjectTypeAndSubjectId(OwnerType subjectType, Long subjectId);

    long countByAction(com.winllc.certalert.domain.AuditAction action);

    @Modifying
    @Query("delete from AuditEvent e where e.occurredAt < :cutoff")
    int deleteByOccurredAtBefore(@Param("cutoff") Instant cutoff);
}

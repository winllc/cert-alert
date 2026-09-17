package com.winllc.certalert.repository;

import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.OwnerType;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /**
     * One entry's history, newest first. The id breaks ties: a sweep writes several records
     * with the same instant, and a page boundary that falls between two of them has to land
     * in the same place every time or paging repeats and skips rows.
     */
    Page<AuditEvent> findBySubjectTypeAndSubjectIdOrderByOccurredAtDescIdDesc(
            OwnerType subjectType, Long subjectId, Pageable pageable);

    long countBySubjectTypeAndSubjectId(OwnerType subjectType, Long subjectId);

    @Modifying
    @Query("delete from AuditEvent e where e.occurredAt < :cutoff")
    int deleteByOccurredAtBefore(@Param("cutoff") Instant cutoff);
}

package com.winllc.certalert.export;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduledReportRepository extends JpaRepository<ScheduledReport, Long> {

    List<ScheduledReport> findAllByOrderByNameAsc();

    List<ScheduledReport> findByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(Instant now);

    long countByOwner(String owner);
}

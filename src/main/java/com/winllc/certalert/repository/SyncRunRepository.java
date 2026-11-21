package com.winllc.certalert.repository;

import com.winllc.certalert.domain.SyncJob;
import com.winllc.certalert.domain.SyncRun;
import com.winllc.certalert.domain.SyncStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SyncRunRepository extends JpaRepository<SyncRun, Long> {

    List<SyncRun> findByOrderByStartedAtDesc(Pageable pageable);

    Optional<SyncRun> findFirstByJobAndStatusInOrderByStartedAtDesc(SyncJob job, List<SyncStatus> statuses);

    /** Whether a run of the job ended in this status having started after the instant. */
    boolean existsByJobAndStatusAndStartedAtAfter(SyncJob job, SyncStatus status, Instant after);
}

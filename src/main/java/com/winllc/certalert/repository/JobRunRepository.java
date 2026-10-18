package com.winllc.certalert.repository;

import com.winllc.certalert.cluster.JobRun;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobRunRepository extends JpaRepository<JobRun, String> {

    List<JobRun> findByOrderByRequestedAtDesc(Pageable pageable);

    /** Says the runs this replica is working on are still being worked on. */
    @Modifying
    @Query("update JobRun r set r.heartbeatAt = :now where r.id in :ids")
    int beat(@Param("ids") Collection<String> ids, @Param("now") Instant now);

    /** Runs whose replica has stopped saying it is working on them. */
    @Modifying
    @Query("""
            update JobRun r set r.state = :failed, r.finishedAt = :now, r.heartbeatAt = :now,
                r.error = 'The replica running it stopped before it finished'
            where r.state in :unfinished and r.heartbeatAt < :before
            """)
    int abandon(
            @Param("unfinished") Collection<JobRun.State> unfinished,
            @Param("failed") JobRun.State failed,
            @Param("before") Instant before,
            @Param("now") Instant now);

    @Modifying
    @Query("delete from JobRun r where r.requestedAt < :before")
    int deleteRequestedBefore(@Param("before") Instant before);
}

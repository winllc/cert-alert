package com.winllc.certalert.repository;

import com.winllc.certalert.domain.OutboundEmail;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboundEmailRepository extends JpaRepository<OutboundEmail, Long> {

    /** What is waiting and due, the longest-waiting first. */
    @Query("""
            select e.id from OutboundEmail e
            where e.state = :pending and e.nextAttemptAt <= :now
            order by e.nextAttemptAt, e.id
            """)
    List<Long> findDue(
            @Param("pending") OutboundEmail.State pending, @Param("now") Instant now, Pageable pageable);

    /** Retires what a newer message replaces, if it has not gone yet. */
    @Modifying
    @Query("""
            update OutboundEmail e set e.state = :superseded
            where e.supersedeKey = :key and e.state = :pending
            """)
    int supersede(
            @Param("key") String key,
            @Param("pending") OutboundEmail.State pending,
            @Param("superseded") OutboundEmail.State superseded);

    /** Forgets what is finished with - sent, given up on or replaced - once it is this old. */
    @Modifying
    @Query("delete from OutboundEmail e where e.state <> :pending and e.createdAt < :before")
    int deleteFinishedBefore(@Param("pending") OutboundEmail.State pending, @Param("before") Instant before);

    long countByState(OutboundEmail.State state);
}

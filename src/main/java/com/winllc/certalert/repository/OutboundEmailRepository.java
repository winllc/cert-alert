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
    @Query("delete from OutboundEmail e where e.state <> :pending and e.state <> :sending and e.createdAt < :before")
    int deleteFinishedBefore(
            @Param("pending") OutboundEmail.State pending,
            @Param("sending") OutboundEmail.State sending,
            @Param("before") Instant before);

    long countByState(OutboundEmail.State state);

    /**
     * Claims a waiting message for sending, if nobody else has: one replica, one send.
     *
     * @param leaseUntil when the claim lapses, should the claimant die before it finishes
     * @return 1 if this caller has it
     */
    @Modifying
    @Query("""
            update OutboundEmail e set e.state = :sending, e.nextAttemptAt = :leaseUntil
            where e.id = :id and e.state = :pending
            """)
    int claim(
            @Param("id") Long id,
            @Param("pending") OutboundEmail.State pending,
            @Param("sending") OutboundEmail.State sending,
            @Param("leaseUntil") Instant leaseUntil);

    /** Puts back what a replica claimed and never finished sending. */
    @Modifying
    @Query("update OutboundEmail e set e.state = :pending where e.state = :sending and e.nextAttemptAt < :now")
    int releaseLapsed(
            @Param("sending") OutboundEmail.State sending,
            @Param("pending") OutboundEmail.State pending,
            @Param("now") Instant now);
}

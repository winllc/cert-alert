package com.winllc.certalert.webhook;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    /** What is waiting and due, the longest-waiting first. */
    @Query("""
            select d.id from WebhookDelivery d
            where d.state = :pending and d.nextAttemptAt <= :now
            order by d.nextAttemptAt, d.id
            """)
    List<Long> findDue(
            @Param("pending") WebhookDelivery.State pending, @Param("now") Instant now, Pageable pageable);

    /** Claims a waiting delivery, if nobody else has: one replica, one post. */
    @Modifying
    @Query("""
            update WebhookDelivery d set d.state = :sending, d.nextAttemptAt = :leaseUntil
            where d.id = :id and d.state = :pending
            """)
    int claim(
            @Param("id") Long id,
            @Param("pending") WebhookDelivery.State pending,
            @Param("sending") WebhookDelivery.State sending,
            @Param("leaseUntil") Instant leaseUntil);

    /** Puts back what a replica claimed and never finished. */
    @Modifying
    @Query("update WebhookDelivery d set d.state = :pending where d.state = :sending and d.nextAttemptAt < :now")
    int releaseLapsed(
            @Param("sending") WebhookDelivery.State sending,
            @Param("pending") WebhookDelivery.State pending,
            @Param("now") Instant now);

    /** Forgets what is finished with once it is this old. */
    @Modifying
    @Query("delete from WebhookDelivery d where d.state in :finished and d.createdAt < :before")
    int deleteFinishedBefore(
            @Param("finished") List<WebhookDelivery.State> finished, @Param("before") Instant before);

    List<WebhookDelivery> findByEndpointIdOrderByCreatedAtDesc(Long endpointId, Pageable pageable);

    long countByState(WebhookDelivery.State state);
}

package com.winllc.certalert.webhook;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    /**
     * What is waiting and due, the longest-waiting first. A replica without the key
     * ({@code keyed} false) is not given deliveries to endpoints whose URL or secret is
     * encrypted: it could not send them, and one that has the key will.
     */
    @Query("""
            select d.id from WebhookDelivery d
            where d.state = :pending and d.nextAttemptAt <= :now
              and (:keyed = true or not exists (
                select e.id from WebhookEndpoint e
                where e.id = d.endpointId and (e.urlEncrypted = true or e.secretEncrypted = true)))
            order by d.nextAttemptAt, d.id
            """)
    List<Long> findDue(
            @Param("pending") WebhookDelivery.State pending,
            @Param("now") Instant now,
            @Param("keyed") boolean keyed,
            Pageable pageable);

    /** Endpoints whose URL or secret is encrypted and that have deliveries waiting and due. */
    @Query("""
            select distinct e.id from WebhookEndpoint e, WebhookDelivery d
            where d.endpointId = e.id and d.state = :pending and d.nextAttemptAt <= :now
              and (e.urlEncrypted = true or e.secretEncrypted = true)
            """)
    List<Long> findEncryptedWithDue(@Param("pending") WebhookDelivery.State pending, @Param("now") Instant now);

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

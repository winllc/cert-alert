package com.winllc.certalert.webhook;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, Long> {

    List<WebhookEndpoint> findAllByOrderByCreatedAtDesc();

    List<WebhookEndpoint> findByEnabledTrue();

    long countByEnabledTrue();

    /** Webhooks saved before private addresses were refused by default, still allowed one. */
    List<WebhookEndpoint> findByPrivateNetworksAllowedTrueOrderById();

    /** Endpoints with a URL or a signing secret still stored as given. */
    @Query("select e from WebhookEndpoint e where e.urlEncrypted = false or e.secretEncrypted = false")
    List<WebhookEndpoint> findUnencrypted();

    /** Replaces a URL stored as given with its encrypted form - only if it is still the one read. */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("""
            update WebhookEndpoint e set e.url = :sealed, e.urlEncrypted = true
            where e.id = :id and e.urlEncrypted = false and e.url = :plain
            """)
    int encryptUrl(@Param("id") Long id, @Param("plain") String plain, @Param("sealed") String sealed);

    /** The same for the signing secret. */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("""
            update WebhookEndpoint e set e.secret = :sealed, e.secretEncrypted = true
            where e.id = :id and e.secretEncrypted = false and e.secret = :plain
            """)
    int encryptSecret(@Param("id") Long id, @Param("plain") String plain, @Param("sealed") String sealed);
}

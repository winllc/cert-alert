package com.winllc.certalert.webhook;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, Long> {

    List<WebhookEndpoint> findAllByOrderByCreatedAtDesc();

    List<WebhookEndpoint> findByEnabledTrue();

    long countByEnabledTrue();
}

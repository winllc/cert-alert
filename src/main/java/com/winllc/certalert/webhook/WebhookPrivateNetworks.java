package com.winllc.certalert.webhook;

import com.winllc.certalert.LogSafe;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Says at startup where webhooks may still reach private addresses, so nothing about the
 * stricter default is silent: with {@code cert-alert.webhooks.block-private-networks=false},
 * every webhook may; with it unset (the default), the webhooks saved before this default -
 * which keep their address until their URL is changed - are named, with what to do about them.
 */
@Component
public class WebhookPrivateNetworks {

    private static final Logger log = LoggerFactory.getLogger(WebhookPrivateNetworks.class);

    private final WebhookProperties properties;
    private final WebhookEndpointRepository endpoints;

    public WebhookPrivateNetworks(WebhookProperties properties, WebhookEndpointRepository endpoints) {
        this.properties = properties;
        this.endpoints = endpoints;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        try {
            warning().ifPresent(log::warn);
        } catch (RuntimeException e) {
            log.warn("Could not check the webhooks against block-private-networks: {}", e.toString());
        }
    }

    /** What is said at startup, if anything. */
    Optional<String> warning() {
        Boolean block = properties.getBlockPrivateNetworks();
        if (Boolean.FALSE.equals(block)) {
            return Optional.of("cert-alert.webhooks.block-private-networks=false: webhooks may be posted to "
                    + "private addresses inside this network. New installations refuse them; name the receivers that "
                    + "need one in cert-alert.webhooks.allowed-hosts and remove the setting.");
        }
        if (block != null) {
            return Optional.empty();
        }
        List<WebhookEndpoint> kept = endpoints.findByPrivateNetworksAllowedTrueOrderById();
        if (kept.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(kept.size() + " webhook(s) saved before private addresses were refused by default "
                + "may still be posted to one, until their URL is changed: "
                + kept.stream().map(e -> e.getId() + " (" + LogSafe.oneLine(e.getName()) + ")").toList()
                + ". To keep a receiver inside the network for good, name it in cert-alert.webhooks.allowed-hosts "
                + "(CERT_ALERT_WEBHOOKS_ALLOWED_HOSTS); set cert-alert.webhooks.block-private-networks=true to refuse "
                + "these now, or false to allow private addresses for every webhook as before.");
    }
}

package com.winllc.certalert.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Private addresses are refused for new webhooks by default; one saved before that default
 * keeps its address until its URL is changed, and is named at startup (CA-24).
 */
@SpringBootTest
@ActiveProfiles("test")
class WebhookPrivateNetworksTest {

    private static final String PRIVATE_URL = "https://10.1.2.3/certalert";

    @Autowired
    private Webhooks webhooks;

    @Autowired
    private WebhookEndpointRepository endpoints;

    @Autowired
    private WebhookSecrets secrets;

    @Autowired
    private WebhookPrivateNetworks startup;

    @Autowired
    private WebhookProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    private Long saved;

    @AfterEach
    void cleanUp() {
        properties.setBlockPrivateNetworks(null);
        if (saved != null) {
            endpoints.deleteById(saved);
        }
    }

    /** A webhook as V29 leaves one that existed at the upgrade: to a private address, and allowed it. */
    private WebhookEndpoint savedBeforeTheUpgrade() {
        WebhookEndpoint endpoint = new WebhookEndpoint("bridge", secrets.storeUrl(PRIVATE_URL), WebhookEndpoint.Format.JSON,
                EnumSet.of(WebhookEvent.JOB_FAILED), "test", Instant.now());
        String secret = secrets.generate();
        endpoint.secret(secrets.storeSecret(secret), secret.substring(0, 12), Instant.now());
        saved = endpoints.save(endpoint).getId();
        jdbc.update("UPDATE webhook_endpoint SET private_networks_allowed = TRUE WHERE id = ?", saved);
        return endpoints.findById(saved).orElseThrow();
    }

    @Test
    void aNewWebhookToAPrivateAddressIsRefusedByDefault() {
        assertThatThrownBy(() -> webhooks.create("bridge", PRIVATE_URL, WebhookEndpoint.Format.JSON,
                Set.of(WebhookEvent.JOB_FAILED), "root"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private address")
                .hasMessageContaining("allowed-hosts");
    }

    @Test
    void oneSavedBeforeKeepsItsAddressUntilItsUrlChanges() {
        WebhookEndpoint before = savedBeforeTheUpgrade();
        assertThat(before.isPrivateNetworksAllowed()).isTrue();
        assertThat(startup.warning()).hasValueSatisfying(warning -> assertThat(warning)
                .contains(saved + " (bridge)", "allowed-hosts"));

        // Renamed, same URL: still allowed.
        WebhookEndpoint renamed = webhooks.update(saved, "bridge two", PRIVATE_URL, null, before.getEvents(), true);
        assertThat(renamed.isPrivateNetworksAllowed()).isTrue();
        // Another private address is a new destination, held to today's rules.
        assertThatThrownBy(() -> webhooks.update(saved, "bridge two", "https://10.1.2.4/certalert", null,
                before.getEvents(), true)).hasMessageContaining("private address");
        // Moved somewhere public, it loses what it had: it cannot go back.
        WebhookEndpoint moved = webhooks.update(saved, "bridge two", "https://93.184.216.34/certalert", null,
                before.getEvents(), true);
        assertThat(moved.isPrivateNetworksAllowed()).isFalse();
        assertThatThrownBy(() -> webhooks.update(saved, "bridge two", PRIVATE_URL, null, before.getEvents(), true))
                .hasMessageContaining("private address");
    }

    @Test
    void falseAllowsThemAllAndSaysSo() {
        properties.setBlockPrivateNetworks(false);
        assertThat(startup.warning()).hasValueSatisfying(warning -> assertThat(warning)
                .contains("block-private-networks=false"));
    }
}

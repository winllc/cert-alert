package com.winllc.certalert.webhook;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbound webhooks, bound from {@code cert-alert.webhooks}. The endpoints themselves are
 * configured by an administrator on the integrations page; this is what they are held to.
 */
@ConfigurationProperties(prefix = "cert-alert.webhooks")
public class WebhookProperties {

    /** Whether webhooks are posted at all. Off keeps what is configured and queues nothing. */
    private boolean enabled = true;

    /**
     * Hosts a webhook may be posted to: a name, {@code *.example.org}, or an address or CIDR
     * range. Empty allows any host the address rules allow. Loopback, link-local and cloud
     * metadata addresses are refused whatever this says.
     */
    private List<String> allowedHosts = new ArrayList<>();

    /**
     * Whether private addresses (10/8, 172.16/12, 192.168/16, 100.64/10, fc00::/7) are
     * refused too. Off by default: a receiver inside the same network - a chat bridge, a
     * ticketing relay - is the usual case on premises.
     */
    private boolean blockPrivateNetworks = false;

    /**
     * A base64 AES key (16, 24 or 32 bytes) the webhooks' signing secrets are encrypted with
     * in the database. Unset, they are stored as they are, and a warning says so at startup.
     * Changing it makes the existing secrets unreadable: rotate each endpoint's secret after.
     */
    private String secretKey;

    private Duration connectTimeout = Duration.ofSeconds(5);

    private Duration readTimeout = Duration.ofSeconds(10);

    /**
     * The most one delivery may take, all of it - connecting, the TLS handshake, sending the
     * body and reading the answer. At this the connection is closed, whatever it is waiting
     * on, so one slow receiver cannot hold the sender (and its lock) for longer.
     */
    private Duration deliveryTimeout = Duration.ofSeconds(30);

    /** How often waiting deliveries are looked at. */
    private Duration pollInterval = Duration.ofSeconds(30);

    /**
     * The most one pass of the sender runs for: no new delivery is tried after it, and what
     * was not tried waits, unclaimed, for the next pass. A delivery under way runs on to its
     * own {@code delivery-timeout}.
     */
    private Duration runBudget = Duration.ofSeconds(40);

    /** The most deliveries one pass tries to any one endpoint. */
    private int endpointTriesPerRun = 10;

    /** The most time one pass spends on any one endpoint; no new try to it starts after. */
    private Duration endpointBudget = Duration.ofSeconds(20);

    /** Whether deliveries are sent in the background. Tests switch it off and send explicitly. */
    private boolean background = true;

    /** The wait after the first failure; it doubles with each one after. */
    private Duration retryInitial = Duration.ofMinutes(1);

    /** The longest wait between two attempts. */
    private Duration retryMax = Duration.ofHours(1);

    /** How long a delivery keeps being tried before it is given up on. */
    private Duration giveUpAfter = Duration.ofDays(1);

    /** How long the delivery log keeps a delivery once it has gone or been given up on. */
    private Duration keep = Duration.ofDays(14);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts;
    }

    public boolean isBlockPrivateNetworks() {
        return blockPrivateNetworks;
    }

    public void setBlockPrivateNetworks(boolean blockPrivateNetworks) {
        this.blockPrivateNetworks = blockPrivateNetworks;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Duration getDeliveryTimeout() {
        return deliveryTimeout;
    }

    public void setDeliveryTimeout(Duration deliveryTimeout) {
        this.deliveryTimeout = deliveryTimeout;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public Duration getRunBudget() {
        return runBudget;
    }

    public void setRunBudget(Duration runBudget) {
        this.runBudget = runBudget;
    }

    public int getEndpointTriesPerRun() {
        return endpointTriesPerRun;
    }

    public void setEndpointTriesPerRun(int endpointTriesPerRun) {
        this.endpointTriesPerRun = endpointTriesPerRun;
    }

    public Duration getEndpointBudget() {
        return endpointBudget;
    }

    public void setEndpointBudget(Duration endpointBudget) {
        this.endpointBudget = endpointBudget;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public boolean isBackground() {
        return background;
    }

    public void setBackground(boolean background) {
        this.background = background;
    }

    public Duration getRetryInitial() {
        return retryInitial;
    }

    public void setRetryInitial(Duration retryInitial) {
        this.retryInitial = retryInitial;
    }

    public Duration getRetryMax() {
        return retryMax;
    }

    public void setRetryMax(Duration retryMax) {
        this.retryMax = retryMax;
    }

    public Duration getGiveUpAfter() {
        return giveUpAfter;
    }

    public void setGiveUpAfter(Duration giveUpAfter) {
        this.giveUpAfter = giveUpAfter;
    }

    public Duration getKeep() {
        return keep;
    }

    public void setKeep(Duration keep) {
        this.keep = keep;
    }
}

package com.winllc.certalert.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Somewhere expiry alerts and job results are posted, signed with a secret of its own. */
@Entity
@Table(name = "webhook_endpoint")
public class WebhookEndpoint {

    /** What the body looks like: CertAlert's own signed JSON, or a chat message. */
    public enum Format {
        /** The event as JSON, signed. What an integration should use. */
        JSON,
        /** A Slack incoming webhook's {@code text} message. */
        SLACK,
        /** A Microsoft Teams workflow's Adaptive Card message. */
        TEAMS
    }

    /** The {@code url} column's size (V28). */
    static final int URL_LENGTH = 4000;

    /** The {@code secret} column's size (V26). */
    static final int SECRET_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "webhook_endpoint_seq")
    @SequenceGenerator(name = "webhook_endpoint_seq", sequenceName = "webhook_endpoint_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    /** The URL, encrypted where a key is configured: a chat webhook's URL is its credential. */
    @Column(nullable = false, length = URL_LENGTH)
    private String url;

    @Column(name = "url_encrypted", nullable = false)
    private boolean urlEncrypted;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Format format;

    @Column(nullable = false, length = 500)
    private String events;

    /** The signing secret, encrypted where a key is configured (see {@link WebhookSecrets}). */
    @Column(nullable = false, length = SECRET_LENGTH)
    private String secret;

    @Column(name = "secret_encrypted", nullable = false)
    private boolean secretEncrypted;

    @Column(name = "secret_prefix", nullable = false, length = 16)
    private String secretPrefix;

    @Column(nullable = false)
    private boolean enabled;

    /**
     * Saved before private addresses were refused by default (V29 sets it on every webhook
     * that existed then), so it keeps a private address while
     * {@code cert-alert.webhooks.block-private-networks} is unset - until its URL changes.
     */
    @Column(name = "private_networks_allowed", nullable = false)
    private boolean privateNetworksAllowed;

    @Column(name = "created_by", nullable = false, length = 320)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebhookEndpoint() {
        // for JPA
    }

    WebhookEndpoint(String name, WebhookSecrets.Stored url, Format format, Set<WebhookEvent> events, String createdBy, Instant now) {
        this.name = name;
        this.url = url.value();
        this.urlEncrypted = url.encrypted();
        this.format = format;
        this.events = WebhookEvent.format(events);
        this.enabled = true;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    String getStoredUrl() {
        return url;
    }

    boolean isUrlEncrypted() {
        return urlEncrypted;
    }

    public Format getFormat() {
        return format;
    }

    public Set<WebhookEvent> getEvents() {
        return WebhookEvent.parse(events);
    }

    String getStoredSecret() {
        return secret;
    }

    boolean isSecretEncrypted() {
        return secretEncrypted;
    }

    public String getSecretPrefix() {
        return secretPrefix;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isPrivateNetworksAllowed() {
        return privateNetworksAllowed;
    }

    /** A new URL is held to today's rules: whatever the old one was allowed stays with it. */
    void urlChanged() {
        this.privateNetworksAllowed = false;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean wants(WebhookEvent event) {
        return enabled && getEvents().contains(event);
    }

    void secret(WebhookSecrets.Stored stored, String prefix, Instant now) {
        this.secret = stored.value();
        this.secretEncrypted = stored.encrypted();
        this.secretPrefix = prefix;
        this.updatedAt = now;
    }

    void update(String name, WebhookSecrets.Stored url, Format format, Set<WebhookEvent> events, boolean enabled, Instant now) {
        this.name = name;
        this.url = url.value();
        this.urlEncrypted = url.encrypted();
        this.format = format;
        this.events = WebhookEvent.format(events);
        this.enabled = enabled;
        this.updatedAt = now;
    }

    /** The events, as ids, in a stable order. */
    public Set<String> eventIds() {
        return getEvents().stream().map(WebhookEvent::id).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    static Set<WebhookEvent> allEvents() {
        return Arrays.stream(WebhookEvent.values()).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}

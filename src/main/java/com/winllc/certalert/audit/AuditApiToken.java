package com.winllc.certalert.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A bearer token for the SIEM pull API ({@code GET /api/audit/events}). Scope: reading the
 * audit trail, nothing else. Only the token's SHA-256 is kept; the token is shown once.
 */
@Entity
@Table(name = "audit_api_token")
public class AuditApiToken {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "audit_api_token_seq")
    @SequenceGenerator(name = "audit_api_token_seq", sequenceName = "audit_api_token_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    /** The first characters of the token, so an administrator can tell which one is which. */
    @Column(nullable = false, length = 16)
    private String prefix;

    @Column(name = "created_by", nullable = false, length = 320)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by", length = 320)
    private String revokedBy;

    protected AuditApiToken() {
        // for JPA
    }

    public AuditApiToken(String name, String tokenHash, String prefix, String createdBy, Instant createdAt) {
        this.name = name;
        this.tokenHash = tokenHash;
        this.prefix = prefix;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevokedBy() {
        return revokedBy;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    void used(Instant at) {
        this.lastUsedAt = at;
    }

    void revoke(String by, Instant at) {
        this.revokedAt = at;
        this.revokedBy = by;
    }
}

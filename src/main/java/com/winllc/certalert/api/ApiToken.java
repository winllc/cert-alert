package com.winllc.certalert.api;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Set;

/**
 * A bearer token for the public API ({@code /api/v1/**}). Only the token's SHA-256 is kept;
 * the token itself is shown once, to the administrator who created it.
 *
 * <p>{@code creatorRoles} is the most the token may do: the roles its creator held when it
 * was made, narrowed when they sign in again (see {@link ApiTokens#creatorSignedIn}).
 */
@Entity
@Table(name = "api_token")
public class ApiToken {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "api_token_seq")
    @SequenceGenerator(name = "api_token_seq", sequenceName = "api_token_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    /** The first characters of the token, so an administrator can tell which one is which. */
    @Column(nullable = false, length = 16)
    private String prefix;

    @Column(nullable = false, length = 500)
    private String scopes;

    @Column(name = "created_by", nullable = false, length = 320)
    private String createdBy;

    @Column(name = "creator_dn", length = 1000)
    private String creatorDn;

    /** How the creator signed in when they made it: X509, LDAP, OIDC, SAML, or null for neither. */
    @Column(name = "creator_method", length = 16)
    private String creatorMethod;

    @Column(name = "creator_roles", nullable = false, length = 200)
    private String creatorRoles;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "last_used_from", length = 64)
    private String lastUsedFrom;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by", length = 320)
    private String revokedBy;

    protected ApiToken() {
        // for JPA
    }

    ApiToken(
            String name,
            String tokenHash,
            String prefix,
            Set<ApiScope> scopes,
            String createdBy,
            String creatorDn,
            String creatorMethod,
            Set<ApiScope.Role> creatorRoles,
            Instant createdAt,
            Instant expiresAt) {
        this.name = name;
        this.tokenHash = tokenHash;
        this.prefix = prefix;
        this.scopes = ApiScope.format(scopes);
        this.createdBy = createdBy;
        this.creatorDn = creatorDn;
        this.creatorMethod = creatorMethod;
        this.creatorRoles = ApiScope.Role.format(creatorRoles);
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
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

    public Set<ApiScope> getScopes() {
        return ApiScope.parse(scopes);
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getCreatorDn() {
        return creatorDn;
    }

    public String getCreatorMethod() {
        return creatorMethod;
    }

    public Set<ApiScope.Role> getCreatorRoles() {
        return ApiScope.Role.parse(creatorRoles);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public String getLastUsedFrom() {
        return lastUsedFrom;
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

    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    void used(Instant at, String from) {
        this.lastUsedAt = at;
        this.lastUsedFrom = from;
    }

    void revoke(String by, Instant at) {
        this.revokedAt = at;
        this.revokedBy = by;
    }

    /** Never widened: the roles a creator held at sign-in, kept to what they held before. */
    void narrowCreatorRoles(Set<ApiScope.Role> current) {
        Set<ApiScope.Role> kept = getCreatorRoles();
        kept.retainAll(current);
        this.creatorRoles = ApiScope.Role.format(kept);
    }
}

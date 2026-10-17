package com.winllc.certalert.audit;

import com.winllc.certalert.repository.AuditApiTokenRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bearer tokens for the SIEM pull API: {@code ensat_} and 32 random bytes, base64url. Shown
 * once when created; only the SHA-256 is kept. Scope: reading the audit trail, nothing else.
 * Creating and revoking one is audited; using one is not (a pull that audited itself would
 * feed itself) - its last use is recorded on the token instead.
 */
@Service
public class AuditTokens {

    public static final String PREFIX = "ensat_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final AuditApiTokenRepository tokens;
    private final AuditLog audit;
    private final Clock clock;

    public AuditTokens(AuditApiTokenRepository tokens, AuditLog audit, Clock clock) {
        this.tokens = tokens;
        this.audit = audit;
        this.clock = clock;
    }

    /** A new token, and the token itself - the only time it is ever available. */
    public record Created(AuditApiToken token, String secret) {}

    @Transactional
    public Created create(String name, String createdBy) {
        String label = name == null ? "" : name.trim();
        if (label.isEmpty() || label.length() > 120) {
            throw new IllegalArgumentException("Give the token a name of up to 120 characters");
        }
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        AuditApiToken token = tokens.save(new AuditApiToken(
                label, AuditFormat.sha256Hex(secret), secret.substring(0, 12), createdBy, Instant.now(clock)));
        audit.record(AuditRecord.of(AuditRecord.Category.AUDIT, "audit.token-created")
                .severity(AuditRecord.Severity.NOTICE)
                .target("audit-token", String.valueOf(token.getId()), label)
                .detail("prefix", token.getPrefix())
                .detail("scope", "audit:read"));
        return new Created(token, secret);
    }

    @Transactional
    public AuditApiToken revoke(Long id, String revokedBy) {
        AuditApiToken token = tokens.findById(id)
                .orElseThrow(() -> new com.winllc.certalert.service.ResourceNotFoundException("No audit API token " + id));
        if (!token.isRevoked()) {
            token.revoke(revokedBy, Instant.now(clock));
            audit.record(AuditRecord.of(AuditRecord.Category.AUDIT, "audit.token-revoked")
                    .severity(AuditRecord.Severity.NOTICE)
                    .target("audit-token", String.valueOf(token.getId()), token.getName())
                    .detail("prefix", token.getPrefix()));
        }
        return token;
    }

    @Transactional(readOnly = true)
    public List<AuditApiToken> list() {
        return tokens.findAllByOrderByCreatedAtDesc();
    }

    /** The live token this bearer value is, recording that it was used. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<AuditApiToken> authenticate(String bearer) {
        if (bearer == null || !bearer.startsWith(PREFIX) || bearer.length() > 200) {
            return Optional.empty();
        }
        return tokens.findByTokenHash(AuditFormat.sha256Hex(bearer))
                .filter(token -> !token.isRevoked())
                .map(token -> {
                    token.used(Instant.now(clock));
                    return token;
                });
    }
}

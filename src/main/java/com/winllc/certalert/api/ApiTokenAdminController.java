package com.winllc.certalert.api;

import com.winllc.certalert.audit.AuditContext;
import com.winllc.certalert.security.SignedInDirectoryUser;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * API tokens, managed by a signed-in administrator; auditors may list them. Never reachable
 * with a token (see {@link ApiEndpoints}).
 *
 * <p>The new token is in the body of the response that created it, marked not to be stored,
 * and nowhere else: not in the session, not in a flash attribute, not in a log.
 */
@RestController
public class ApiTokenAdminController {

    public static final String PATH = "/api/v1/admin/api-tokens";

    private final ApiTokens tokens;

    public ApiTokenAdminController(ApiTokens tokens) {
        this.tokens = tokens;
    }

    /** A token as the page lists it: never its hash, never the token. */
    public record TokenRow(
            Long id,
            String name,
            String prefix,
            List<String> scopes,
            String createdBy,
            Instant createdAt,
            Instant expiresAt,
            Instant lastUsedAt,
            String lastUsedFrom,
            Instant revokedAt,
            String revokedBy,
            String state) {

        static TokenRow of(ApiToken token) {
            Instant now = Instant.now();
            String state = token.isRevoked() ? "revoked" : token.isExpired(now) ? "expired" : "active";
            return new TokenRow(token.getId(), token.getName(), token.getPrefix(),
                    token.getScopes().stream().map(ApiScope::id).toList(), token.getCreatedBy(), token.getCreatedAt(),
                    token.getExpiresAt(), token.getLastUsedAt(), token.getLastUsedFrom(), token.getRevokedAt(),
                    token.getRevokedBy(), state);
        }
    }

    /**
     * @param scopes scope ids, {@code certificates:read} and so on
     * @param expiresAt when it stops working; null for never, where the installation allows that
     */
    public record TokenRequest(String name, List<String> scopes, Instant expiresAt) {}

    /** The new token, shown this once. */
    public record CreatedToken(TokenRow token, String secret) {}

    /** The scopes there are, for the form. */
    public record ScopeOption(String id, String description, String requires) {}

    @GetMapping(PATH)
    public List<TokenRow> list(Authentication authentication) {
        requireReader(authentication);
        return tokens.list().stream().map(TokenRow::of).toList();
    }

    @GetMapping(PATH + "/scopes")
    public List<ScopeOption> scopes() {
        return Arrays.stream(ApiScope.values())
                .map(scope -> new ScopeOption(scope.id(), scope.description(), scope.requires().name()))
                .toList();
    }

    @PostMapping(PATH)
    public ResponseEntity<CreatedToken> create(@RequestBody TokenRequest request, Authentication authentication) {
        requireAdministrator(authentication);
        Set<ApiScope> scopes = request.scopes() == null ? Set.of()
                : request.scopes().stream().map(ApiScope::of).collect(Collectors.toSet());
        ApiTokens.Created created = tokens.create(request.name(), scopes, request.expiresAt(), authentication);
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(new CreatedToken(TokenRow.of(created.token()), created.secret()));
    }

    @DeleteMapping(PATH + "/{id}")
    public TokenRow revoke(@PathVariable Long id, Authentication authentication) {
        requireAdministrator(authentication);
        return TokenRow.of(tokens.revoke(id, AuditContext.actorOf(authentication).id()));
    }

    private static void requireAdministrator(Authentication authentication) {
        if (authentication instanceof ApiTokenAuthentication || !SignedInDirectoryUser.isAdmin(authentication)) {
            throw new AccessDeniedException("API tokens are an administrator's to manage");
        }
    }

    private static void requireReader(Authentication authentication) {
        if (authentication instanceof ApiTokenAuthentication
                || (!SignedInDirectoryUser.isAdmin(authentication) && !SignedInDirectoryUser.isAuditor(authentication))) {
            throw new AccessDeniedException("API tokens are an administrator's or an auditor's to read");
        }
    }
}

package com.winllc.certalert.api;

import com.winllc.certalert.audit.AuditContext;
import com.winllc.certalert.audit.AuditFormat;
import com.winllc.certalert.audit.AuditLog;
import com.winllc.certalert.audit.AuditRecord;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.security.DirectoryPrincipalResolver;
import com.winllc.certalert.service.ResourceNotFoundException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public API's bearer tokens: {@code cat_} and 32 random bytes, base64url.
 *
 * <p><b>Shown once.</b> The token is in the response to the request that created it and
 * nowhere else: only its SHA-256 is kept, it is never logged, and it is never put in the
 * session. A value that looks like one is redacted wherever it reaches the audit trail.
 *
 * <p><b>Never more than its creator.</b> An administrator creates a token, choosing its scopes
 * from those their own roles allow. The token keeps those roles as its ceiling and loses
 * whatever its creator loses: their roles are worked out again from the directory as the
 * token is used (for a creator who signs in with a certificate or a directory password, whose
 * roles the directory decides), and the ceiling is narrowed whenever they sign in again (for
 * any creator, a single sign-on one included, whose provider's groups are only known then).
 *
 * <p><b>Audited.</b> Creating and revoking one, a refusal of a real token (revoked, expired,
 * its creator's role gone), each request that changes something, and its reads summarised to
 * one {@code api.token-used} per token per {@code cert-alert.api.use-audit-interval}.
 */
@Service
public class ApiTokens {

    private static final Logger log = LoggerFactory.getLogger(ApiTokens.class);

    public static final String PREFIX = "cat_";

    /** How long the last-used time may lag, so a busy client is not a write per request. */
    private static final Duration LAST_USED_RESOLUTION = Duration.ofMinutes(1);

    /** At most one refusal of the same token is audited in this long. */
    private static final Duration REFUSAL_AUDIT_INTERVAL = Duration.ofMinutes(1);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiTokenRepository tokens;
    private final AuditLog audit;
    private final DirectoryPrincipalResolver resolver;
    private final ApiProperties properties;
    private final Clock clock;

    private final Map<Long, CachedRoles> creatorRoles = new ConcurrentHashMap<>();
    private final Map<Long, Instant> usesAudited = new ConcurrentHashMap<>();
    private final Map<Long, Instant> refusalsAudited = new ConcurrentHashMap<>();
    private final Map<String, Instant> signInsSeen = new ConcurrentHashMap<>();

    public ApiTokens(
            ApiTokenRepository tokens,
            AuditLog audit,
            DirectoryPrincipalResolver resolver,
            ApiProperties properties,
            Clock clock) {
        this.tokens = tokens;
        this.audit = audit;
        this.resolver = resolver;
        this.properties = properties;
        this.clock = clock;
    }

    /** A new token, and the token itself - the only time it is ever available. */
    public record Created(ApiToken token, String secret) {}

    /** Why a token was refused, for the response and the audit trail. */
    public enum Refusal {
        UNKNOWN, REVOKED, EXPIRED, CREATOR_ROLE_GONE, DISABLED
    }

    /** What a bearer value turned out to be. */
    public record Outcome(ApiTokenAuthentication authentication, Refusal refusal) {

        static Outcome refused(Refusal refusal) {
            return new Outcome(null, refusal);
        }

        public boolean accepted() {
            return authentication != null;
        }
    }

    /**
     * @param creator whoever is creating it, signed in; an administrator (the API's own rules
     *     and this both say so)
     * @param expiresAt when it stops working, or null for never where that is allowed
     */
    @Transactional
    public Created create(String name, Collection<ApiScope> scopes, Instant expiresAt, Authentication creator) {
        String label = name == null ? "" : name.trim();
        if (label.isEmpty() || label.length() > 120) {
            throw new IllegalArgumentException("Give the token a name of up to 120 characters");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("Give the token at least one scope");
        }
        Set<ApiScope.Role> roles = rolesOf(creator);
        if (!roles.contains(ApiScope.Role.ADMIN)) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "API tokens are an administrator's to create");
        }
        for (ApiScope scope : scopes) {
            if (!scope.allowedFor(roles)) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Your roles do not allow a token with the " + scope.id() + " scope");
            }
        }
        Instant now = Instant.now(clock);
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("A token's expiry has to be in the future");
        }
        Duration max = properties.getMaxLifetime();
        if (max != null && (expiresAt == null || expiresAt.isAfter(now.plus(max)))) {
            throw new IllegalArgumentException(
                    "Tokens on this installation expire within " + max.toDays() + " days (cert-alert.api.max-lifetime)");
        }

        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        String dn = null;
        String method = null;
        if (creator.getPrincipal() instanceof DirectoryPrincipal principal) {
            dn = principal.getDirectoryDn();
            method = principal.getMethod().name();
        }
        AuditRecord.Actor actor = AuditContext.actorOf(creator);
        ApiToken token = tokens.save(new ApiToken(
                label,
                AuditFormat.sha256Hex(secret),
                secret.substring(0, 12),
                EnumSet.copyOf(scopes),
                actor.id(),
                dn,
                method,
                roles,
                now,
                expiresAt));
        audit.record(AuditRecord.of(AuditRecord.Category.CONFIGURATION, "api.token-created")
                .severity(AuditRecord.Severity.NOTICE)
                .target("api-token", String.valueOf(token.getId()), label)
                .detail("prefix", token.getPrefix())
                .detail("scopes", ApiScope.format(token.getScopes()))
                .detail("expiresAt", expiresAt == null ? "never" : expiresAt.toString()));
        return new Created(token, secret);
    }

    @Transactional
    public ApiToken revoke(Long id, String revokedBy) {
        ApiToken token = tokens.findById(id).orElseThrow(() -> new ResourceNotFoundException("No API token " + id));
        if (!token.isRevoked()) {
            token.revoke(revokedBy, Instant.now(clock));
            creatorRoles.remove(id);
            audit.record(AuditRecord.of(AuditRecord.Category.CONFIGURATION, "api.token-revoked")
                    .severity(AuditRecord.Severity.NOTICE)
                    .target("api-token", String.valueOf(token.getId()), token.getName())
                    .detail("prefix", token.getPrefix()));
        }
        return token;
    }

    @Transactional(readOnly = true)
    public List<ApiToken> list() {
        return tokens.findAllByOrderByCreatedAtDesc();
    }

    /**
     * What this bearer value is: a live token, with the scopes its creator's roles still
     * allow, or the reason it is not one.
     *
     * @param from the client's address, recorded as where the token was last used from
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome authenticate(String bearer, String from) {
        if (!properties.isTokensEnabled()) {
            return Outcome.refused(Refusal.DISABLED);
        }
        if (bearer == null || !bearer.startsWith(PREFIX) || bearer.length() > 200) {
            return Outcome.refused(Refusal.UNKNOWN);
        }
        Optional<ApiToken> found = tokens.findByTokenHash(AuditFormat.sha256Hex(bearer));
        if (found.isEmpty()) {
            return Outcome.refused(Refusal.UNKNOWN);
        }
        ApiToken token = found.get();
        Instant now = Instant.now(clock);
        if (token.isRevoked()) {
            return refuse(token, Refusal.REVOKED, now);
        }
        if (token.isExpired(now)) {
            return refuse(token, Refusal.EXPIRED, now);
        }
        Set<ApiScope.Role> ceiling = ceilingOf(token, now);
        Set<ApiScope> effective = EnumSet.noneOf(ApiScope.class);
        for (ApiScope scope : ApiScope.expand(token.getScopes())) {
            if (scope.allowedFor(ceiling)) {
                effective.add(scope);
            }
        }
        if (effective.isEmpty()) {
            return refuse(token, Refusal.CREATOR_ROLE_GONE, now);
        }
        if (token.getLastUsedAt() == null || token.getLastUsedAt().plus(LAST_USED_RESOLUTION).isBefore(now)) {
            token.used(now, from == null || from.length() > 64 ? null : from);
        }
        return new Outcome(new ApiTokenAuthentication(token.getId(), token.getName(), token.getCreatedBy(), effective), null);
    }

    /**
     * Records that a token was used, at most once per token per interval: the reads a client
     * makes would otherwise be most of the trail. Called once the request is authenticated,
     * so the event carries the token as its actor.
     */
    public void recordUse(ApiTokenAuthentication token, String method, String path) {
        Instant now = Instant.now(clock);
        Instant last = usesAudited.get(token.tokenId());
        if (last != null && last.plus(properties.getUseAuditInterval()).isAfter(now)) {
            return;
        }
        usesAudited.put(token.tokenId(), now);
        audit.recordQuietly(AuditRecord.of(AuditRecord.Category.AUTHENTICATION, "api.token-used")
                .actor(AuditContext.actorOf(token))
                .target("api-token", String.valueOf(token.tokenId()), token.tokenName())
                .detail("method", method)
                .detail("path", path)
                .detail("scopes", ApiScope.format(token.scopes())));
    }

    /** A request that changes something, recorded as it happens. */
    public void recordWrite(ApiTokenAuthentication token, String method, String path, int status) {
        audit.recordQuietly(AuditRecord.of(AuditRecord.Category.DATA, "api.request")
                .actor(AuditContext.actorOf(token))
                .outcome(status < 400 ? AuditRecord.Outcome.SUCCESS : AuditRecord.Outcome.FAILURE)
                .target("api-token", String.valueOf(token.tokenId()), token.tokenName())
                .detail("method", method)
                .detail("path", path)
                .detail("status", status));
    }

    /**
     * Narrows the ceiling of every live token this person created to the roles they hold
     * now. Never widens one: a token keeps no more than its creator had when it was made.
     */
    @EventListener
    @Transactional
    public void creatorSignedIn(AuthenticationSuccessEvent event) {
        Authentication authentication = event.getAuthentication();
        if (authentication == null || authentication instanceof ApiTokenAuthentication) {
            return;
        }
        try {
            narrowTokensOf(authentication);
        } catch (RuntimeException e) {
            // Signing in goes on; the ceiling is checked again as the tokens are used.
            log.warn("Could not narrow the API tokens of a creator who signed in: {}", e.toString());
        }
    }

    private void narrowTokensOf(Authentication authentication) {
        Set<ApiScope.Role> roles = rolesOf(authentication);
        String who = AuditContext.actorOf(authentication).id();
        String dn = authentication.getPrincipal() instanceof DirectoryPrincipal principal ? principal.getDirectoryDn() : null;
        // A certificate signs somebody in on every request; the same answer twice in five
        // minutes is not worth reading the table again for.
        String key = (dn == null ? "" : dn.toLowerCase(Locale.ROOT)) + "|" + who + "|" + ApiScope.Role.format(roles);
        Instant now = Instant.now(clock);
        Instant seen = signInsSeen.get(key);
        if (seen != null && seen.plus(Duration.ofMinutes(5)).isAfter(now)) {
            return;
        }
        signInsSeen.put(key, now);
        if (signInsSeen.size() > 10_000) {
            signInsSeen.clear();
        }
        List<ApiToken> theirs = new java.util.ArrayList<>(tokens.findByCreatedByAndRevokedAtIsNull(who));
        if (dn != null) {
            tokens.findByCreatorDnIgnoreCaseAndRevokedAtIsNull(dn).stream()
                    .filter(token -> theirs.stream().noneMatch(t -> t.getId().equals(token.getId())))
                    .forEach(theirs::add);
        }
        for (ApiToken token : theirs) {
            Set<ApiScope.Role> before = token.getCreatorRoles();
            token.narrowCreatorRoles(roles);
            if (!before.equals(token.getCreatorRoles())) {
                creatorRoles.remove(token.getId());
                log.info("API token {} now allows no more than its creator's roles: {}",
                        token.getId(), ApiScope.Role.format(token.getCreatorRoles()));
            }
        }
    }

    private Outcome refuse(ApiToken token, Refusal refusal, Instant now) {
        Instant last = refusalsAudited.get(token.getId());
        if (last == null || !last.plus(REFUSAL_AUDIT_INTERVAL).isAfter(now)) {
            refusalsAudited.put(token.getId(), now);
            audit.recordQuietly(AuditRecord.of(AuditRecord.Category.AUTHENTICATION, "api.token-refused")
                    .outcome(AuditRecord.Outcome.DENIED)
                    .severity(AuditRecord.Severity.WARNING)
                    .actor(new AuditRecord.Actor(
                            AuditRecord.ActorType.SERVICE, "api-token:" + token.getId(), token.getName(), List.of()))
                    .target("api-token", String.valueOf(token.getId()), token.getName())
                    .reason(refusal.name().toLowerCase(Locale.ROOT).replace('_', '-')));
        }
        return Outcome.refused(refusal);
    }

    /**
     * The most a token may do now: the roles recorded for its creator, and - where the
     * directory decides that creator's roles - no more than it says they hold today.
     */
    private Set<ApiScope.Role> ceilingOf(ApiToken token, Instant now) {
        Set<ApiScope.Role> ceiling = token.getCreatorRoles();
        if (!directoryDecides(token)) {
            return ceiling;
        }
        CachedRoles cached = creatorRoles.get(token.getId());
        Set<ApiScope.Role> current;
        if (cached != null && cached.until().isAfter(now)) {
            current = cached.roles();
        } else {
            current = currentDirectoryRoles(token);
            creatorRoles.put(token.getId(), new CachedRoles(current, now.plus(properties.getCreatorRolesCacheFor())));
        }
        ceiling.retainAll(current);
        return ceiling;
    }

    /** A certificate or a directory password: the directory and the configuration say what they are. */
    private static boolean directoryDecides(ApiToken token) {
        return token.getCreatorDn() != null
                && ("X509".equals(token.getCreatorMethod()) || "LDAP".equals(token.getCreatorMethod()));
    }

    private Set<ApiScope.Role> currentDirectoryRoles(ApiToken token) {
        DirectoryPrincipal.AuthenticationMethod method =
                DirectoryPrincipal.AuthenticationMethod.valueOf(token.getCreatorMethod());
        Optional<DirectoryUser> user = resolver.findByDn(token.getCreatorDn());
        DirectoryPrincipal principal = user.isPresent()
                ? resolver.principalFor(user.get(), method)
                : resolver.unknownPrincipal(token.getCreatedBy(), token.getCreatorDn(), uidOf(token.getCreatorDn()), method);
        return rolesOf(principal.getAuthorities());
    }

    /** The uid a DN leads with, which is how an entry the cache does not hold is still named. */
    private static List<String> uidOf(String dn) {
        try {
            List<Rdn> rdns = new LdapName(dn).getRdns();
            if (!rdns.isEmpty()) {
                Rdn leaf = rdns.getLast();
                if ("uid".equalsIgnoreCase(leaf.getType())) {
                    return List.of(String.valueOf(leaf.getValue()));
                }
            }
        } catch (InvalidNameException | RuntimeException e) {
            // no uid to offer
        }
        return List.of();
    }

    static Set<ApiScope.Role> rolesOf(Authentication authentication) {
        return authentication == null ? EnumSet.noneOf(ApiScope.Role.class) : rolesOf(authentication.getAuthorities());
    }

    private static Set<ApiScope.Role> rolesOf(Collection<? extends GrantedAuthority> authorities) {
        Set<ApiScope.Role> roles = EnumSet.noneOf(ApiScope.Role.class);
        for (GrantedAuthority authority : authorities) {
            String name = authority.getAuthority();
            if (DirectoryPrincipalResolver.ROLE_ADMIN.equals(name)) {
                roles.add(ApiScope.Role.ADMIN);
            } else if (DirectoryPrincipalResolver.ROLE_AUDITOR.equals(name)) {
                roles.add(ApiScope.Role.AUDITOR);
            } else if (DirectoryPrincipalResolver.ROLE_USER.equals(name)) {
                roles.add(ApiScope.Role.USER);
            }
        }
        return roles;
    }

    private record CachedRoles(Set<ApiScope.Role> roles, Instant until) {}
}

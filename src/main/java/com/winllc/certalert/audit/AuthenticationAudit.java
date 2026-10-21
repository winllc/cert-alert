package com.winllc.certalert.audit;

import com.winllc.certalert.observability.EnsMetrics;
import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.security.RefusedCertificateException;
import com.winllc.certalert.security.SessionPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Locale;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * {@code auth.login} and {@code auth.logout}, from Spring Security's own authentication
 * events - so every way in is covered: a client certificate ({@code x509}), a directory
 * password ({@code ldap}) and single sign-on ({@code oidc}, {@code saml}).
 *
 * <p>A failed sign-in is recorded with the attempted name as an {@code anonymous} actor and
 * the reason. A sign-in that cannot be recorded does not happen: the failure to write is
 * turned into an authentication failure. (The directory, not this application, locks
 * accounts, so there is no {@code auth.lockout}; passwords are the directory's too.)
 *
 * <p>A browser presenting a certificate the directory does not publish is refused on every
 * request it makes until it gives up, so those failures are collapsed: one event per
 * certificate subject a minute, with a count, through {@link AccessDenials}' sibling below.
 *
 * <p>A client certificate is checked on every request, but recorded as a sign-in once per
 * session (see {@link #alreadySignedIn}).
 *
 * <p>A certificate the directory does publish but that has been revoked, or is out of date,
 * is refused the same way and recorded with that as its reason and the certificate's
 * fingerprint. A revoked one is raised as an alert: somebody holds the key to a certificate
 * its authority has withdrawn, which is what revocation is for.
 */
@Component
public class AuthenticationAudit {

    private final AuditLog audit;
    private final FailureCollapser certificateFailures;
    private final org.springframework.beans.factory.ObjectProvider<EnsMetrics> metrics;

    public AuthenticationAudit(
            AuditLog audit,
            java.time.Clock clock,
            org.springframework.beans.factory.ObjectProvider<EnsMetrics> metrics) {
        this.audit = audit;
        this.certificateFailures = new FailureCollapser(audit, clock);
        this.metrics = metrics;
    }

    private void count(String method, boolean succeeded) {
        EnsMetrics counting = metrics.getIfAvailable();
        if (counting != null) {
            counting.authAttempt(method, succeeded);
        }
    }

    @EventListener
    public void succeeded(AuthenticationSuccessEvent event) {
        Authentication authentication = event.getAuthentication();
        if (authentication != null && authentication.getName() != null) {
            failures.remove(authentication.getName().toLowerCase(Locale.ROOT));
        }
        if (alreadySignedIn(authentication)) {
            return;
        }
        count(methodOf(authentication), true);
        AuditRecord record = AuditRecord.of(AuditRecord.Category.AUTHENTICATION, "auth.login")
                .actor(AuditContext.actorOf(authentication))
                .detail("method", methodOf(authentication));
        try {
            audit.record(record);
        } catch (RuntimeException e) {
            throw new InternalAuthenticationServiceException("The sign-in could not be audited", e);
        }
    }

    @EventListener
    public void failed(AbstractAuthenticationFailureEvent event) {
        if (event.getException() instanceof ProviderNotFoundException) {
            // The parent authentication manager saying it has no provider for a certificate,
            // after this chain's own has already refused it - and published that, with the
            // real reason. Recording it as well would make every refusal two.
            return;
        }
        Authentication attempt = event.getAuthentication();
        String method = methodOf(attempt);
        count(method, false);
        String attempted = attempt == null ? null : attempt.getName();
        AuditRecord record = AuditRecord.of(AuditRecord.Category.AUTHENTICATION, "auth.login")
                .outcome(AuditRecord.Outcome.FAILURE)
                .actor(AuditRecord.Actor.anonymous(attempted))
                .reason(reasonOf(event.getException()))
                .detail("method", method);
        if (event.getException() instanceof RefusedCertificateException refused) {
            record.detail("fingerprint", refused.getFingerprint());
            if (refused.getReason() == RefusedCertificateException.Reason.REVOKED) {
                record.severity(AuditRecord.Severity.ALERT);
            }
        }
        if ("x509".equals(method)) {
            certificateFailures.failed(record, attempted);
            return;
        }
        int recent = recentFailures(attempted);
        if (recent >= REPEATED_FAILURES) {
            // The directory locks accounts, not this application; repeated failures for
            // one name are the nearest thing it sees, and are raised as an alert.
            record.severity(AuditRecord.Severity.ALERT).detail("recentFailures", recent);
        }
        audit.recordQuietly(record);
    }

    /** Failures in a row this name may have before they are an alert. */
    static final int REPEATED_FAILURES = 5;

    private static final java.time.Duration FAILURE_WINDOW = java.time.Duration.ofMinutes(15);

    private final java.util.Map<String, java.util.Deque<java.time.Instant>> failures =
            new java.util.concurrent.ConcurrentHashMap<>();

    private int recentFailures(String name) {
        // A single sign-on refused before anybody was named attempts no name; those are not
        // one person's run of failures.
        if (name == null || name.isBlank()) {
            return 0;
        }
        java.time.Instant now = java.time.Instant.now();
        java.util.Deque<java.time.Instant> times = failures.computeIfAbsent(
                name.toLowerCase(Locale.ROOT), k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        times.addLast(now);
        while (!times.isEmpty() && times.peekFirst().isBefore(now.minus(FAILURE_WINDOW))) {
            times.pollFirst();
        }
        if (failures.size() > 10_000) {
            failures.clear();
        }
        return times.size();
    }

    /**
     * A client certificate is checked afresh on every request it comes with - so revoking or
     * unpublishing it takes effect at once - but only the first request of a session is a
     * sign-in. The rest are the same person in the same session, already recorded.
     */
    static boolean alreadySignedIn(Authentication authentication) {
        if (authentication == null || !"x509".equals(methodOf(authentication))) {
            return false;
        }
        HttpServletRequest request = AuditContext.request();
        HttpSession session = request == null ? null : request.getSession(false);
        if (session == null) {
            return false;
        }
        try {
            return session.getAttribute(SessionPolicy.SIGNED_IN_AS) instanceof Authentication previous
                    && java.util.Objects.equals(previous.getName(), authentication.getName());
        } catch (IllegalStateException invalidated) {
            return false;
        }
    }

    @EventListener
    public void loggedOut(LogoutSuccessEvent event) {
        audit.recordQuietly(AuditRecord.of(AuditRecord.Category.AUTHENTICATION, "auth.logout")
                .actor(AuditContext.actorOf(event.getAuthentication())));
    }

    /** Writes any collapsed certificate failures whose minute is up. */
    public void flushExpired() {
        certificateFailures.flush(false);
    }

    public void flush() {
        certificateFailures.flush(true);
    }

    static String methodOf(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        if (authentication.getPrincipal() instanceof DirectoryPrincipal principal) {
            return principal.getMethod().name().toLowerCase(Locale.ROOT);
        }
        if (authentication instanceof PreAuthenticatedAuthenticationToken) {
            return "x509";
        }
        if (authentication instanceof UsernamePasswordAuthenticationToken) {
            return "ldap";
        }
        if (authentication instanceof AuditTokenAuthentication) {
            return "api-token";
        }
        if (authentication instanceof org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken
                || authentication instanceof org.springframework.security.oauth2.client.authentication.OAuth2AuthorizationCodeAuthenticationToken
                || authentication instanceof org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken) {
            return "oidc";
        }
        if (authentication instanceof org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationToken
                || authentication instanceof org.springframework.security.saml2.provider.service.authentication.Saml2Authentication) {
            return "saml";
        }
        return "password";
    }

    static String reasonOf(AuthenticationException e) {
        if (e instanceof BadCredentialsException) {
            return "bad credentials";
        }
        if (e instanceof UsernameNotFoundException) {
            return "unknown user or certificate";
        }
        if (e instanceof AccountStatusException) {
            return "account " + e.getClass().getSimpleName().replace("Exception", "").toLowerCase(Locale.ROOT);
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** Collapses repeated identical failures into one event a minute, with a count. */
    static final class FailureCollapser {
        private final AuditLog audit;
        private final java.time.Clock clock;
        private final java.util.Map<String, Object[]> open = new java.util.LinkedHashMap<>();

        FailureCollapser(AuditLog audit, java.time.Clock clock) {
            this.audit = audit;
            this.clock = clock;
        }

        void failed(AuditRecord record, String key) {
            AuditContext.capture(record);
            java.time.Instant now = java.time.Instant.now(clock);
            record.time(now);
            String k = key + "|" + record.reason();
            synchronized (open) {
                Object[] pending = open.get(k);
                if (pending != null) {
                    pending[1] = ((Integer) pending[1]) + 1;
                    return;
                }
                open.put(k, new Object[] {record, 1});
            }
        }

        void flush(boolean all) {
            java.util.List<Object[]> due = new java.util.ArrayList<>();
            java.time.Instant now = java.time.Instant.now(clock);
            synchronized (open) {
                for (var it = open.values().iterator(); it.hasNext(); ) {
                    Object[] pending = it.next();
                    AuditRecord record = (AuditRecord) pending[0];
                    if (all || java.time.Duration.between(record.time(), now).compareTo(AccessDenials.WINDOW) >= 0) {
                        due.add(pending);
                        it.remove();
                    }
                }
            }
            for (Object[] pending : due) {
                AuditRecord record = (AuditRecord) pending[0];
                record.detail("count", pending[1]);
                audit.recordQuietly(record);
            }
        }
    }
}

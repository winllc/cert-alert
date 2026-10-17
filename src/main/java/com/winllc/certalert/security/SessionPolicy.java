package com.winllc.certalert.security;

import com.winllc.certalert.audit.SessionAudit;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

/**
 * How long a signed-in session lasts, and how many one person may hold (the ENS baseline's
 * {@code ENS_SESSION_*}).
 *
 * <ul>
 *   <li><b>Idle.</b> Each session is given its idle timeout when somebody signs in to it -
 *       the administrators' shorter one where they hold the role - and the container ends it
 *       when it has gone unused that long. Seen here as the session is destroyed, and
 *       recorded as {@code idle} where the time since it was last used says that is why.
 *   <li><b>Absolute.</b> A session ends {@code ENS_SESSION_ABSOLUTE_TIMEOUT} after sign-in
 *       however busy it is, on its first request after that ({@code absolute}).
 *   <li><b>Concurrent.</b> Beyond {@code ENS_SESSION_MAX_PER_USER}, signing in ends the
 *       person's <em>oldest</em> session - the earliest sign-in, not the least recently used
 *       ({@code concurrent}). It is recorded then, and the session is refused on its next
 *       request.
 * </ul>
 *
 * <p>Every way of signing in arrives here the same way - see {@link SessionPolicyFilter},
 * which notices a session's first signed-in request - so a client certificate is held to the
 * same limits as a password. A certificate is checked on every request rather than kept in
 * the session, so for its holder an ended session means a new one on the next request - and,
 * with the consent banner on, agreeing to it again. That first request also gives the
 * session a new id, so an id somebody fixed on the browser before sign-in is not the one
 * that ends up signed in.
 *
 * <p>Ended sessions go to the sign-in page ({@code /login?expired}); an API call gets a 401.
 * One instance is assumed (see the chart's {@code replicaCount}): the register of who holds
 * which sessions is in memory, as the sessions themselves are.
 */
@Component
public class SessionPolicy implements HttpSessionListener, HttpSessionIdListener {

    private static final Logger log = LoggerFactory.getLogger(SessionPolicy.class);

    /** When this session was signed in to; absent until it has been. */
    public static final String SIGNED_IN_AT = SessionPolicy.class.getName() + ".signedInAt";

    /**
     * Who signed in to it. Kept here as well as wherever the sign-in keeps it: a client
     * certificate is checked afresh on every request and never stored in the session, so
     * without this an idle session's record would have nobody to name.
     */
    public static final String SIGNED_IN_AS = SessionPolicy.class.getName() + ".signedInAs";

    /** Why the application ended this session, so its destruction is not mistaken for idling. */
    static final String ENDED = SessionPolicy.class.getName() + ".ended";

    /** The container's expiry runs on a timer, so a session can be a little past its time. */
    private static final Duration IDLE_SLACK = Duration.ofSeconds(5);

    private final EnsSecurityProperties properties;
    private final SessionAudit audit;
    private final Clock clock;

    /** Each person's signed-in sessions, by who they are. */
    private final Map<String, List<Held>> byUser = new ConcurrentHashMap<>();
    /** Sessions ended for being one too many, refused on their next request. */
    private final Set<String> pushedOut = ConcurrentHashMap.newKeySet();

    private record Held(String sessionId, Instant signedInAt) {}

    public SessionPolicy(EnsSecurityProperties properties, SessionAudit audit, Clock clock) {
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Applies the policy to a signed-in request.
     *
     * @return why its session has ended, or null where it carries on
     */
    String check(HttpServletRequest request, HttpSession session, Authentication authentication) {
        Object signedIn = session.getAttribute(SIGNED_IN_AT);
        if (!(signedIn instanceof Instant at)) {
            signedIn(request, authentication);
            return null;
        }
        if (pushedOut.contains(session.getId())) {
            return "concurrent";
        }
        Duration absolute = properties.getSession().getAbsoluteTimeout();
        if (absolute != null && !absolute.isZero() && !Instant.now(clock).isBefore(at.plus(absolute))) {
            audit.expired(authentication, session.getId(), "absolute", request.getRemoteAddr());
            return "absolute";
        }
        return null;
    }

    /** A sign-out: not an expiry, whatever the session's clock says. */
    public void loggingOut(
            HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, Authentication who) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.setAttribute(ENDED, "logout");
            } catch (IllegalStateException alreadyGone) {
                // nothing to mark
            }
        }
    }

    /** Ends a session this policy has ruled on: marked first, so its destruction is not read as idling. */
    void end(HttpSession session, String reason) {
        try {
            session.setAttribute(ENDED, reason);
            session.invalidate();
        } catch (IllegalStateException alreadyGone) {
            // invalidated by somebody else in the meantime
        }
    }

    /** A session's first signed-in request: a new id, its timeouts, and its place in the register. */
    private void signedIn(HttpServletRequest request, Authentication authentication) {
        request.changeSessionId();
        HttpSession session = request.getSession(false);
        Instant now = Instant.now(clock);
        session.setAttribute(SIGNED_IN_AT, now);
        session.setAttribute(SIGNED_IN_AS, authentication);
        Duration idle = SignedInDirectoryUser.isAdmin(authentication)
                ? properties.getSession().effectiveAdminIdleTimeout()
                : properties.getSession().getIdleTimeout();
        if (idle != null && !idle.isZero()) {
            session.setMaxInactiveInterval((int) Math.min(Integer.MAX_VALUE, idle.toSeconds()));
        }

        String user = keyOf(authentication);
        List<Held> held = byUser.computeIfAbsent(user, key -> new ArrayList<>());
        List<Held> ended = new ArrayList<>();
        synchronized (held) {
            held.add(new Held(session.getId(), now));
            int max = properties.getSession().getMaxPerUser();
            if (max > 0) {
                held.sort(Comparator.comparing(Held::signedInAt));
                while (held.size() > max) {
                    ended.add(held.removeFirst());
                }
            }
        }
        for (Held oldest : ended) {
            pushedOut.add(oldest.sessionId());
            log.info("Ending the oldest session of {}: over ENS_SESSION_MAX_PER_USER ({})",
                    authentication.getName(), properties.getSession().getMaxPerUser());
            audit.expired(authentication, oldest.sessionId(), "concurrent", null);
        }
    }

    @Override
    public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
        String newId = event.getSession().getId();
        for (List<Held> held : byUser.values()) {
            synchronized (held) {
                held.replaceAll(entry -> entry.sessionId().equals(oldSessionId)
                        ? new Held(newId, entry.signedInAt())
                        : entry);
            }
        }
        if (pushedOut.remove(oldSessionId)) {
            pushedOut.add(newId);
        }
    }

    /**
     * Forgets a session, and records it as idle where nothing else ended it and it had gone
     * unused for its whole timeout. A sign-out, a shutdown or a session this policy ended are
     * none of those.
     */
    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        HttpSession session = event.getSession();
        String id = session.getId();
        boolean wasPushedOut = pushedOut.remove(id);
        forget(id);
        try {
            if (session.getAttribute(SIGNED_IN_AT) == null || session.getAttribute(ENDED) != null || wasPushedOut) {
                return;
            }
            Authentication who = authenticationIn(session);
            if (who == null || session.getMaxInactiveInterval() <= 0) {
                return;
            }
            long unusedMillis = clock.millis() - session.getLastAccessedTime();
            long timeoutMillis = session.getMaxInactiveInterval() * 1000L;
            if (unusedMillis >= timeoutMillis - IDLE_SLACK.toMillis()) {
                audit.expired(who, id, "idle", null);
            }
        } catch (IllegalStateException alreadyGone) {
            // a container that will not answer questions about a session it is destroying
        }
    }

    private void forget(String sessionId) {
        byUser.values().forEach(held -> {
            synchronized (held) {
                held.removeIf(entry -> entry.sessionId().equals(sessionId));
            }
        });
        byUser.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                return entry.getValue().isEmpty();
            }
        });
    }

    private static Authentication authenticationIn(HttpSession session) {
        if (session.getAttribute(SIGNED_IN_AS) instanceof Authentication who) {
            return who;
        }
        Object context = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return context instanceof SecurityContext security ? security.getAuthentication() : null;
    }

    /** One person, however they signed in: their entry where they have one, else their name. */
    static String keyOf(Authentication authentication) {
        if (authentication.getPrincipal() instanceof DirectoryPrincipal principal
                && principal.getDirectoryDn() != null) {
            return "dn:" + principal.getDirectoryDn().trim().toLowerCase(Locale.ROOT);
        }
        return "name:" + String.valueOf(authentication.getName()).trim().toLowerCase(Locale.ROOT);
    }
}

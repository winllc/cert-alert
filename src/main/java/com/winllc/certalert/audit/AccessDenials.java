package com.winllc.certalert.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * {@code access.denied}: every 403 the application answers - a role refused, the license
 * ({@code license_required}, {@code license_read_only}, {@code feature_not_licensed}), a CSRF
 * failure - with the method, the path and the reason code.
 *
 * <p>Identical denials (same actor, path and reason) are collapsed so a page polling an
 * endpoint it may not use cannot flood the trail: the first is recorded at once with
 * {@code details.count} 1; repeats within the next 60 seconds are only counted, and recorded
 * as one event with {@code details.count} and {@code details.collapsed} when the window
 * closes - by the background flush every few seconds, or at shutdown.
 *
 * <p>At most {@value #MAX_WINDOWS} windows are open. Beyond that a new denial is collapsed per
 * actor and reason whatever its path ({@code details.anyPath}), so a client walking distinct
 * forbidden paths cannot grow the map without bound. The same collapsing records a wrong pull-API
 * token as a failed sign-in ({@link #collapse}).
 */
@Component
public class AccessDenials {

    public static final Duration WINDOW = Duration.ofSeconds(60);
    /** Most windows open at once. */
    static final int MAX_WINDOWS = 10_000;

    private final AuditLog audit;
    private final Clock clock;
    private final Map<String, Window> open = new ConcurrentHashMap<>();
    int maxWindows = MAX_WINDOWS;

    public AccessDenials(AuditLog audit, Clock clock) {
        this.audit = audit;
        this.clock = clock;
    }

    /** Notes a 403 being sent for this request. */
    public void denied(HttpServletRequest request, String reason, String message) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        AuditRecord event = AuditRecord.of(AuditRecord.Category.AUTHORIZATION, "access.denied")
                .outcome(AuditRecord.Outcome.DENIED)
                .reason(reason)
                .target("path", path, null)
                .detail("method", request.getMethod())
                .detail("path", path)
                .detail("reasonCode", reason)
                .detail("message", message);
        AuditContext.capture(event);
        String actor = event.actor().type().id() + "|" + event.actor().id();
        collapse(actor + "|" + request.getMethod() + " " + path + "|" + reason, actor + "|*|" + reason, event);
    }

    /**
     * A wrong or revoked token on the SIEM pull API: a failed sign-in (method {@code api-token}),
     * collapsed per source address. The token itself is never recorded.
     */
    public void tokenRefused(HttpServletRequest request) {
        AuditRecord event = AuditRecord.of(AuditRecord.Category.AUTHENTICATION, "auth.login")
                .outcome(AuditRecord.Outcome.FAILURE)
                .actor(AuditRecord.Actor.anonymous(null))
                .source(AuditContext.sourceOf(request))
                .reason("invalid or revoked token")
                .detail("method", "api-token")
                .detail("path", request.getRequestURI().substring(request.getContextPath().length()));
        collapse("auth.login|api-token|" + request.getRemoteAddr(), "auth.login|api-token", event);
    }

    /**
     * Records {@code event} now, unless one with the same key was recorded in the last 60 seconds,
     * which then counts it. While {@link #MAX_WINDOWS} windows are open, a new one takes
     * {@code whenFull} as its key instead. The event's request context is captured first.
     */
    public void collapse(String key, String whenFull, AuditRecord event) {
        AuditContext.capture(event);
        Instant now = Instant.now(clock);
        event.time(now);
        String k = open.size() >= maxWindows && !open.containsKey(key) ? whenFull : key;
        if (!k.equals(key) && "access.denied".equals(event.action())) {
            event.detail("anyPath", true);
        }
        boolean[] first = {false};
        open.compute(k, (ignored, window) -> {
            if (window == null) {
                first[0] = true;
                return new Window(event);
            }
            window.repeats++;
            return window;
        });
        if (first[0]) {
            audit.recordQuietly(event.detail("count", 1));
        }
    }

    /** Records the repeats of every window that has closed. */
    public void flushExpired() {
        Instant now = Instant.now(clock);
        List<Window> due = new ArrayList<>();
        for (Iterator<Window> it = open.values().iterator(); it.hasNext(); ) {
            Window window = it.next();
            if (Duration.between(window.first.time(), now).compareTo(WINDOW) >= 0) {
                it.remove();
                due.add(window);
            }
        }
        due.forEach(this::writeRepeats);
    }

    /** Records the repeats of every window now, closed or not: at shutdown, and in tests. */
    public void flush() {
        List<Window> due = new ArrayList<>();
        for (Iterator<Window> it = open.values().iterator(); it.hasNext(); ) {
            due.add(it.next());
            it.remove();
        }
        due.forEach(this::writeRepeats);
    }

    /** How many windows are open (tests). */
    int openWindows() {
        return open.size();
    }

    private void writeRepeats(Window window) {
        if (window.repeats == 0) {
            return;
        }
        AuditRecord first = window.first;
        AuditRecord repeat = AuditRecord.of(first.category(), first.action())
                .outcome(first.outcome())
                .severity(first.severity())
                .reason(first.reason())
                .actor(first.actor())
                .source(first.source())
                .time(first.time());
        if (first.target() != null) {
            repeat.target(first.target().type(), first.target().id(), first.target().name());
        }
        first.details().forEach(repeat::detail);
        repeat.detail("count", window.repeats).detail("collapsed", true).markContextCaptured();
        audit.recordQuietly(repeat);
    }

    private static final class Window {
        final AuditRecord first;
        int repeats;

        Window(AuditRecord first) {
            this.first = first;
        }
    }
}

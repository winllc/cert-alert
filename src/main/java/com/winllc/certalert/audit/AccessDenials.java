package com.winllc.certalert.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * {@code access.denied}: every 403 the application answers - a role refused, the license
 * ({@code license_required}, {@code license_read_only}, {@code feature_not_licensed}), a CSRF
 * failure - with the method, the path and the reason code.
 *
 * <p>Identical denials (same actor, path and reason) within 60 seconds are one event with
 * {@code details.count}, so a page polling an endpoint it may not use cannot flood the
 * trail. The event is written when its window closes - by the background flush every few
 * seconds, or at shutdown - carrying the first denial's time, actor and source.
 */
@Component
public class AccessDenials {

    public static final Duration WINDOW = Duration.ofSeconds(60);

    private final AuditLog audit;
    private final Clock clock;
    private final Map<String, Pending> open = new LinkedHashMap<>();

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
                .target("path", path, null);
        AuditContext.capture(event);
        event.time(Instant.now(clock));
        String key = event.actor().type().id() + "|" + event.actor().id() + "|" + request.getMethod() + " " + path
                + "|" + reason;
        List<Pending> due = new ArrayList<>();
        synchronized (open) {
            due.addAll(expired(event.time()));
            Pending pending = open.get(key);
            if (pending != null) {
                pending.count++;
            } else {
                open.put(key, new Pending(event, request.getMethod(), path, reason, message));
            }
        }
        due.forEach(this::write);
    }

    /** Writes every window that has closed. */
    public void flushExpired() {
        List<Pending> due;
        synchronized (open) {
            due = expired(Instant.now(clock));
        }
        due.forEach(this::write);
    }

    /** Writes every window now, closed or not: at shutdown, and in tests. */
    public void flush() {
        List<Pending> due;
        synchronized (open) {
            due = new ArrayList<>(open.values());
            open.clear();
        }
        due.forEach(this::write);
    }

    private List<Pending> expired(Instant now) {
        List<Pending> due = new ArrayList<>();
        for (Iterator<Pending> it = open.values().iterator(); it.hasNext(); ) {
            Pending pending = it.next();
            if (Duration.between(pending.event.time(), now).compareTo(WINDOW) >= 0) {
                due.add(pending);
                it.remove();
            }
        }
        return due;
    }

    private void write(Pending pending) {
        pending.event
                .detail("method", pending.method)
                .detail("path", pending.path)
                .detail("reasonCode", pending.reason)
                .detail("message", pending.message)
                .detail("count", pending.count);
        audit.recordQuietly(pending.event);
    }

    private static final class Pending {
        final AuditRecord event;
        final String method;
        final String path;
        final String reason;
        final String message;
        int count = 1;

        Pending(AuditRecord event, String method, String path, String reason, String message) {
            this.event = event;
            this.method = method;
            this.path = path;
            this.reason = reason;
            this.message = message;
        }
    }
}

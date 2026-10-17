package com.winllc.certalert.audit;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * {@code auth.session-expired}: a signed-in session ended by the application rather than by
 * the person - {@code idle}, {@code absolute} or {@code concurrent} (see
 * {@code SessionPolicy}). Attributed to whoever was signed in, and to that session, which is
 * not necessarily the one being served when it is noticed: a sign-in that pushes somebody's
 * oldest session out is recorded against the session it ended.
 */
@Component
public class SessionAudit {

    private final AuditLog audit;

    public SessionAudit(AuditLog audit) {
        this.audit = audit;
    }

    /**
     * @param sourceIp the client's address where the expiry is noticed on one of its own
     *     requests; null where it is not
     */
    public void expired(Authentication who, String sessionId, String reason, String sourceIp) {
        AuditRecord event = AuditRecord.of(AuditRecord.Category.AUTHENTICATION, "auth.session-expired")
                .actor(AuditContext.actorOf(who))
                .reason(reason)
                .detail("reason", reason);
        if (sourceIp != null) {
            event.source(new AuditRecord.Source(sourceIp, null));
        }
        // Said here rather than taken from whatever request is being served.
        event.session(sessionId == null ? null : AuditFormat.sha256Hex(sessionId).substring(0, 16));
        event.markContextCaptured();
        audit.recordQuietly(event);
    }
}

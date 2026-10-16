package com.winllc.certalert.audit;

import com.winllc.certalert.license.Licensing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * At startup: chains the history from before the format, records {@code system.started}, and
 * checks the license mode against the last one the trail recorded. At shutdown: writes any
 * collapsed denials still open, and records {@code system.stopped}.
 */
@Component
public class AuditStartup {

    private static final Logger log = LoggerFactory.getLogger(AuditStartup.class);

    private final AuditLog audit;
    private final LicenseAudit licenseAudit;
    private final Licensing licensing;
    private final AccessDenials denials;
    private final AuthenticationAudit authentication;
    private volatile boolean started;

    public AuditStartup(
            AuditLog audit,
            LicenseAudit licenseAudit,
            Licensing licensing,
            AccessDenials denials,
            AuthenticationAudit authentication) {
        this.audit = audit;
        this.licenseAudit = licenseAudit;
        this.licensing = licensing;
        this.denials = denials;
        this.authentication = authentication;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void started() {
        try {
            audit.chainHistory();
        } catch (RuntimeException e) {
            log.error("Could not chain the audit history from before ens-audit/1; retrying with the next event", e);
        }
        audit.recordQuietly(AuditRecord.of(AuditRecord.Category.SYSTEM, "system.started")
                .actor(AuditRecord.Actor.system("startup"))
                .detail("version", audit.origin().version()));
        licenseAudit.start(licensing.manager().current());
        started = true;
    }

    @EventListener(ContextClosedEvent.class)
    public void stopping() {
        if (!started) {
            return;
        }
        started = false;
        try {
            denials.flush();
            authentication.flush();
            audit.recordQuietly(AuditRecord.of(AuditRecord.Category.SYSTEM, "system.stopped")
                    .actor(AuditRecord.Actor.system("shutdown"))
                    .detail("version", audit.origin().version()));
        } catch (RuntimeException e) {
            log.warn("Could not record the shutdown in the audit trail: {}", e.toString());
        }
    }
}

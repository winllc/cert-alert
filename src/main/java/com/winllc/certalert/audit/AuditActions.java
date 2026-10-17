package com.winllc.certalert.audit;

import com.winllc.certalert.domain.AuditAction;
import java.util.List;
import java.util.TreeSet;

/**
 * Every action id this application records, for the audit page's filter. Ids are stable:
 * a SIEM searches on them, so once shipped one is never renamed.
 */
public final class AuditActions {

    /** Everything other than the entry changes, which {@link AuditAction} lists. */
    public static final List<String> RECORDED = List.of(
            // authentication
            "auth.login", "auth.logout", "auth.consent-acknowledged",
            // authorization
            "access.denied",
            // configuration
            "settings.changed", "server-attribute.defined", "server-attribute.changed", "server-attribute.retired",
            // license
            "license.installed", "license.rejected", "license.mode-changed", "license.limit-exceeded",
            // data
            "project.created", "project.updated", "project.deleted",
            // system
            "system.started", "system.stopped", "job.completed", "job.failed", "job.skipped",
            // audit
            "audit.exported", "audit.verified", "audit.token-created", "audit.token-revoked", "audit.pruned",
            "audit.delivery-failed", "audit.delivery-recovered");

    private AuditActions() {}

    /** Every known id, and any others the store holds, sorted. */
    public static List<String> all(List<String> stored) {
        TreeSet<String> ids = new TreeSet<>(RECORDED);
        for (AuditAction action : AuditAction.values()) {
            ids.add(action.id());
        }
        ids.addAll(stored);
        return List.copyOf(ids);
    }
}

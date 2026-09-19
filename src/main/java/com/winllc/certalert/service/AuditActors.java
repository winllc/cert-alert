package com.winllc.certalert.service;

import com.winllc.certalert.security.DirectoryPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Who an audit record is attributed to.
 *
 * <p>Either a person, as the directory names them, or the job that did it. Nothing is ever
 * recorded as having happened by itself: a record whose actor is unknown does not answer
 * the question it exists to answer.
 */
public final class AuditActors {

    /** The scheduled sweep of the whole directory, or one triggered by hand. */
    public static final String SYNC = "sync";

    /** The connector following the directory's changelog. */
    public static final String CHANGELOG = "changelog";

    /** The job that re-evaluates cached expiry without reading the directory. */
    public static final String REFRESH = "expiry refresh";

    /** The job that asks the issuing authorities what they have revoked. */
    public static final String REVOCATION = "revocation";

    /** The job that deletes finished certificates from the directory. */
    public static final String CLEANUP = "certificate cleanup";

    /** The job that deletes entries the directory has stopped publishing. */
    public static final String PRUNE = "prune";

    /** A person who did it but could not be identified - a job running outside a request. */
    public static final String SYSTEM = "system";

    private AuditActors() {}

    /**
     * Whoever is signed in, or {@code fallback} where nobody is - a scheduled job has no
     * security context, and a sweep somebody triggered from the UI has theirs.
     */
    public static String current(String fallback) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return fallback;
        }
        if (authentication.getPrincipal() instanceof DirectoryPrincipal principal) {
            return principal.getUsername();
        }
        String name = authentication.getName();
        return name == null || name.isBlank() || "anonymousUser".equals(name) ? fallback : name;
    }
}

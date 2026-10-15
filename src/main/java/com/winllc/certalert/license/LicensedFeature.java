package com.winllc.certalert.license;

import java.util.List;
import java.util.Optional;

/**
 * The features a license turns on. Everything not listed here is core and runs unlicensed:
 * the directory sync and refresh, the people and servers tables and pages, expiry status,
 * signing in, in-app notifications, the logging alert channel, the banner, the audit trail
 * and the administration page, server attributes and points of contact.
 *
 * <p>The one place the split is written down. Each feature names the addresses that belong
 * to it, which {@link LicenseInterceptor} refuses while the feature is not licensed; the
 * scheduled jobs, the alert channels and the templates ask {@link LicenseService} by id.
 * Moving a feature between core and licensed is an edit here and nowhere else - bar the
 * job or the button that belongs to it.
 */
public enum LicensedFeature {

    REVOCATION("revocation", "Revocation checking",
            "/api/v1/revocation", "/api/v1/revocation/**"),
    TLS_PROBE("tls-probe", "TLS endpoint probing",
            "/api/v1/servers/*/probe"),
    CHANGELOG("changelog", "Real-time changelog sync",
            "/api/v1/changelog", "/api/v1/changelog/**"),
    DIRECTORY_CLEANUP("directory-cleanup", "Directory cleanup",
            "/api/v1/sync/prune", "/api/v1/sync/prune/**",
            "/api/v1/sync/certificate-cleanup", "/api/v1/sync/certificate-cleanup/**"),
    EMAIL_NOTIFICATIONS("email-notifications", "Email notifications",
            "/api/v1/notifications/digest"),
    PROJECTS("projects", "Projects",
            "/projects", "/projects/**", "/api/v1/projects", "/api/v1/projects/**"),
    METRICS("metrics", "Metrics",
            "/metrics", "/api/v1/metrics");

    private final String id;
    private final String label;
    private final List<String> paths;

    LicensedFeature(String id, String label, String... paths) {
        this.id = id;
        this.label = label;
        this.paths = List.of(paths);
    }

    /** What a license file's {@code features} line calls it. */
    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** Path patterns, in {@code PathPattern} syntax, that belong to this feature. */
    public List<String> paths() {
        return paths;
    }

    public static Optional<LicensedFeature> byId(String id) {
        for (LicensedFeature feature : values()) {
            if (feature.id.equals(id)) {
                return Optional.of(feature);
            }
        }
        return Optional.empty();
    }
}

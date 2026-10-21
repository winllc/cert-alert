package com.winllc.certalert.api;

import java.util.List;
import org.springframework.http.HttpMethod;

/**
 * Every endpoint an API token may call, and the scope it needs. Anything not listed here is
 * refused to a token (403), whatever its scopes: an endpoint added later is closed to tokens
 * until somebody decides which scope it belongs to, and the OpenAPI document is held to this
 * list by a test ({@code OpenApiDocumentTest}).
 *
 * <p>Not listed, on purpose: managing API tokens, webhooks and the audit API's tokens (a
 * token does not mint tokens or point the application's secrets somewhere new), a person's
 * own notifications and saved views (a token is not a person), and the tables' DataTables
 * endpoints (the list endpoints below are the API's version of them).
 */
public final class ApiEndpoints {

    private ApiEndpoints() {}

    /** A method and a path pattern ({@code *} is one segment), and the scope it needs. */
    public record Rule(HttpMethod method, String pattern, ApiScope scope) {}

    public static final List<Rule> RULES = List.of(
            // The certificate inventory, servers and projects, and summaries over them.
            get("/api/v1/certificates", ApiScope.CERTIFICATES_READ),
            get("/api/v1/certificates/*", ApiScope.CERTIFICATES_READ),
            get("/api/v1/summary/expiry", ApiScope.CERTIFICATES_READ),
            get("/api/v1/servers", ApiScope.CERTIFICATES_READ),
            get("/api/v1/servers/*", ApiScope.CERTIFICATES_READ),
            get("/api/v1/servers/*/certificates", ApiScope.CERTIFICATES_READ),
            get("/api/v1/servers/*/contacts", ApiScope.CERTIFICATES_READ),
            get("/api/v1/servers/*/attributes", ApiScope.CERTIFICATES_READ),
            get("/api/v1/stats/servers", ApiScope.CERTIFICATES_READ),
            get("/api/v1/projects", ApiScope.CERTIFICATES_READ),
            get("/api/v1/revocation", ApiScope.CERTIFICATES_READ),

            // People.
            get("/api/v1/people", ApiScope.PEOPLE_READ),
            get("/api/v1/people/*", ApiScope.PEOPLE_READ),
            get("/api/v1/users/*/certificates", ApiScope.PEOPLE_READ),
            get("/api/v1/users/*/addresses", ApiScope.PEOPLE_READ),
            get("/api/v1/stats/users", ApiScope.PEOPLE_READ),

            // Jobs: how they went, and starting them.
            get("/api/v1/jobs", ApiScope.JOBS_READ),
            get("/api/v1/jobs/*", ApiScope.JOBS_READ),
            get("/api/v1/sync/runs", ApiScope.JOBS_READ),
            get("/api/v1/changelog", ApiScope.JOBS_READ),
            post("/api/v1/sync", ApiScope.JOBS_RUN),
            post("/api/v1/sync/users", ApiScope.JOBS_RUN),
            post("/api/v1/sync/servers", ApiScope.JOBS_RUN),
            post("/api/v1/sync/refresh", ApiScope.JOBS_RUN),
            post("/api/v1/changelog/poll", ApiScope.JOBS_RUN),
            post("/api/v1/revocation/check", ApiScope.JOBS_RUN),
            post("/api/v1/notifications/digest", ApiScope.JOBS_RUN),

            // Curating the inventory.
            post("/api/v1/servers/*/contacts", ApiScope.INVENTORY_WRITE),
            rule(HttpMethod.DELETE, "/api/v1/servers/*/contacts/*", ApiScope.INVENTORY_WRITE),
            rule(HttpMethod.PUT, "/api/v1/servers/*/attributes/*", ApiScope.INVENTORY_WRITE),
            post("/api/v1/projects", ApiScope.INVENTORY_WRITE),
            rule(HttpMethod.PUT, "/api/v1/projects/*", ApiScope.INVENTORY_WRITE),
            rule(HttpMethod.DELETE, "/api/v1/projects/*", ApiScope.INVENTORY_WRITE),
            post("/api/v1/projects/*/users/*", ApiScope.INVENTORY_WRITE),
            rule(HttpMethod.DELETE, "/api/v1/projects/*/users/*", ApiScope.INVENTORY_WRITE),
            post("/api/v1/projects/*/servers/*", ApiScope.INVENTORY_WRITE),
            rule(HttpMethod.DELETE, "/api/v1/projects/*/servers/*", ApiScope.INVENTORY_WRITE),

            // An administrator's: what removes data, who runs a project, outbound probes,
            // the audit trail and the definitions of server attributes.
            post("/api/v1/sync/prune", ApiScope.ADMIN),
            get("/api/v1/sync/prune/preview", ApiScope.ADMIN),
            post("/api/v1/sync/certificate-cleanup", ApiScope.ADMIN),
            get("/api/v1/sync/certificate-cleanup/preview", ApiScope.ADMIN),
            post("/api/v1/projects/*/admins/*", ApiScope.ADMIN),
            rule(HttpMethod.DELETE, "/api/v1/projects/*/admins/*", ApiScope.ADMIN),
            post("/api/v1/servers/*/probe", ApiScope.ADMIN),
            get("/api/v1/audit/summary", ApiScope.ADMIN),
            get("/api/v1/users/*/audit", ApiScope.ADMIN),
            get("/api/v1/servers/*/audit", ApiScope.ADMIN),
            get("/api/v1/admin/audit/events", ApiScope.ADMIN),
            get("/api/v1/admin/audit/events/*", ApiScope.ADMIN),
            get("/api/v1/admin/audit/export", ApiScope.ADMIN),
            post("/api/v1/admin/audit/verify", ApiScope.ADMIN),
            get("/api/v1/admin/audit/delivery", ApiScope.ADMIN),
            get("/api/v1/admin/server-attributes", ApiScope.ADMIN),
            post("/api/v1/admin/server-attributes", ApiScope.ADMIN),
            rule(HttpMethod.PUT, "/api/v1/admin/server-attributes/*", ApiScope.ADMIN),
            rule(HttpMethod.DELETE, "/api/v1/admin/server-attributes/*", ApiScope.ADMIN),
            get("/api/v1/license", ApiScope.ADMIN));

    private static Rule get(String pattern, ApiScope scope) {
        return new Rule(HttpMethod.GET, pattern, scope);
    }

    private static Rule post(String pattern, ApiScope scope) {
        return new Rule(HttpMethod.POST, pattern, scope);
    }

    private static Rule rule(HttpMethod method, String pattern, ApiScope scope) {
        return new Rule(method, pattern, scope);
    }
}

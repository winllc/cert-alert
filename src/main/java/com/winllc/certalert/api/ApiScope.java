package com.winllc.certalert.api;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What an API token may do. Each scope needs a role of its creator: a token never does
 * more than the person who made it could have done signed in.
 *
 * <p>{@link #ADMIN} implies every other scope; {@link #JOBS_RUN} implies {@link #JOBS_READ},
 * so a client that starts a job can ask how it went.
 */
public enum ApiScope {

    /** Certificates, servers, projects and the expiry summaries; the inventory export. */
    CERTIFICATES_READ("certificates:read", Role.USER, "Read certificates, servers, projects and expiry summaries"),

    /** People and the certificates they hold. */
    PEOPLE_READ("people:read", Role.USER, "Read people and their certificates"),

    /** Jobs started by hand and the sweep history: a result can name the people a round-up wrote to. */
    JOBS_READ("jobs:read", Role.AUDITOR, "Read job runs and sync history"),

    /** Start a sweep, a changelog poll, a revocation check or the round-up. */
    JOBS_RUN("jobs:run", Role.ADMIN, "Start directory sweeps, revocation checks and the round-up"),

    /** Points of contact, projects and server attribute values. */
    INVENTORY_WRITE("inventory:write", Role.ADMIN, "Change points of contact, projects and server attributes"),

    /**
     * Everything the API offers an administrator - pruning, the audit trail, attribute
     * definitions - except managing API tokens and webhooks, which stays with people.
     */
    ADMIN("admin", Role.ADMIN, "Everything above, plus pruning, cleanup and the audit trail");

    /** The roles a creator may hold, weakest first. */
    public enum Role {
        USER, AUDITOR, ADMIN;

        /** Whether holding these roles covers this one. An administrator reads what an auditor reads. */
        boolean coveredBy(Set<Role> held) {
            return switch (this) {
                case USER -> !held.isEmpty();
                case AUDITOR -> held.contains(AUDITOR) || held.contains(ADMIN);
                case ADMIN -> held.contains(ADMIN);
            };
        }

        public static Set<Role> parse(String stored) {
            Set<Role> roles = EnumSet.noneOf(Role.class);
            if (stored == null) {
                return roles;
            }
            for (String part : stored.split(",")) {
                String value = part.trim().toUpperCase(Locale.ROOT);
                if (value.startsWith("ROLE_")) {
                    value = value.substring(5);
                }
                for (Role role : values()) {
                    if (role.name().equals(value)) {
                        roles.add(role);
                    }
                }
            }
            return roles;
        }

        public static String format(Collection<Role> roles) {
            return roles.stream().sorted().map(Role::name).collect(Collectors.joining(","));
        }
    }

    private final String id;
    private final Role requires;
    private final String description;

    ApiScope(String id, Role requires, String description) {
        this.id = id;
        this.requires = requires;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public Role requires() {
        return requires;
    }

    public String description() {
        return description;
    }

    /** The authority a token holding this scope carries. */
    public String authority() {
        return "SCOPE_" + id;
    }

    public boolean allowedFor(Set<Role> roles) {
        return requires.coveredBy(roles);
    }

    /** These scopes and every scope they imply. */
    public static Set<ApiScope> expand(Collection<ApiScope> scopes) {
        Set<ApiScope> expanded = EnumSet.noneOf(ApiScope.class);
        expanded.addAll(scopes);
        if (expanded.contains(ADMIN)) {
            expanded.addAll(EnumSet.allOf(ApiScope.class));
        }
        if (expanded.contains(JOBS_RUN)) {
            expanded.add(JOBS_READ);
        }
        return expanded;
    }

    /** {@code certificates:read}, case and spacing aside. */
    public static ApiScope of(String id) {
        String value = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        for (ApiScope scope : values()) {
            if (scope.id.equals(value)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("'%s' is not an API scope; the scopes are %s".formatted(
                id, Arrays.stream(values()).map(ApiScope::id).collect(Collectors.joining(", "))));
    }

    public static Set<ApiScope> parse(String stored) {
        Set<ApiScope> scopes = new LinkedHashSet<>();
        if (stored == null || stored.isBlank()) {
            return scopes;
        }
        for (String part : stored.split(",")) {
            if (!part.isBlank()) {
                scopes.add(of(part));
            }
        }
        return scopes;
    }

    public static String format(Collection<ApiScope> scopes) {
        return scopes.stream().sorted().map(ApiScope::id).collect(Collectors.joining(","));
    }
}

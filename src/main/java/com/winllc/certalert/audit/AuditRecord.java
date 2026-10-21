package com.winllc.certalert.audit;

import com.winllc.certalert.domain.AuditEvent;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One audit event before it has its place in the chain: everything {@code ens-audit/1} says
 * about it except seq, the installation's fields and the hashes, which the writer adds.
 *
 * <p>Built fluently - {@code AuditRecord.of(Category.CONFIGURATION, "settings.changed")
 * .detail("leadDays.old", 30).detail("leadDays.new", 14)} - and handed to {@link AuditLog}.
 * Who did it and from where are filled in from the request and the security context when it
 * is recorded, unless they were set here.
 *
 * <p>Secrets never get as far as the chain: a detail whose name says it is a password, a
 * token, a key, a PIN, a code or a session id is stored as {@code "[redacted]"}, and so is a
 * value that looks like one of this application's API tokens.
 */
public final class AuditRecord {

    public static final String REDACTED = "[redacted]";

    /** What this application's tokens and signing secrets start with: the audit API's, the public API's, a webhook's. */
    private static final List<String> TOKEN_PREFIXES = List.of(
            AuditTokens.PREFIX,
            com.winllc.certalert.api.ApiTokens.PREFIX,
            com.winllc.certalert.webhook.WebhookSecrets.PREFIX);

    /** The longest a detail string, a reason or a name may be. */
    public static final int MAX_STRING = 1000;

    public enum Category {
        AUTHENTICATION, AUTHORIZATION, ACCOUNT, CONFIGURATION, DATA, LICENSE, SYSTEM, AUDIT;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Category of(String id) {
            return valueOf(id.toUpperCase(Locale.ROOT));
        }
    }

    public enum Outcome {
        SUCCESS, FAILURE, DENIED;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Outcome of(String id) {
            return valueOf(id.toUpperCase(Locale.ROOT));
        }
    }

    public enum Severity {
        INFO(6), NOTICE(5), WARNING(4), ALERT(1);

        private final int syslog;

        Severity(int syslog) {
            this.syslog = syslog;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The RFC 5424 severity it is sent to syslog as. */
        public int syslog() {
            return syslog;
        }

        public static Severity of(String id) {
            return valueOf(id.toUpperCase(Locale.ROOT));
        }
    }

    public enum ActorType {
        USER, SERVICE, SYSTEM, ANONYMOUS;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Actor(ActorType type, String id, String name, List<String> roles) {
        public Actor {
            Objects.requireNonNull(type);
            roles = roles == null ? List.of() : List.copyOf(roles);
        }

        public static Actor system(String id) {
            return new Actor(ActorType.SYSTEM, id, null, List.of());
        }

        public static Actor anonymous(String attempted) {
            return new Actor(ActorType.ANONYMOUS, attempted, null, List.of());
        }
    }

    public record Source(String ip, String userAgent) {}

    public record Target(String type, String id, String name) {}

    /** Detail names that hold something never to be written down. */
    private static final Set<String> SECRET_WORDS = Set.of(
            "password", "passwd", "pwd", "passphrase", "secret", "token", "tokencode", "apikey", "privatekey",
            "otp", "pin", "passcode", "seed", "credential", "credentials", "sessionid", "session", "cookie",
            "authorization");

    private static final Pattern WORD_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+");

    private final Category category;
    private final String action;
    private String id = UUID.randomUUID().toString();
    private Instant time;
    private Outcome outcome = Outcome.SUCCESS;
    private Severity severity;
    private Actor actor;
    private Source source;
    private Target target;
    private String reason;
    private final Map<String, Object> details = new LinkedHashMap<>();
    private String requestId;
    private String session;
    private boolean contextCaptured;

    /** The entry change this event also is, where it is one. */
    private AuditEvent entity;

    private AuditRecord(Category category, String action) {
        this.category = Objects.requireNonNull(category);
        this.action = Objects.requireNonNull(action);
    }

    public static AuditRecord of(Category category, String action) {
        return new AuditRecord(category, action);
    }

    public AuditRecord outcome(Outcome outcome) {
        this.outcome = outcome;
        return this;
    }

    public AuditRecord severity(Severity severity) {
        this.severity = severity;
        return this;
    }

    public AuditRecord actor(Actor actor) {
        this.actor = actor;
        return this;
    }

    public AuditRecord source(Source source) {
        this.source = source;
        return this;
    }

    public AuditRecord target(String type, String id, String name) {
        this.target = type == null && id == null && name == null ? null
                : new Target(type, clip(id), clip(name));
        return this;
    }

    public AuditRecord reason(String reason) {
        this.reason = reason == null ? null : clip(redactValue(reason));
        return this;
    }

    public AuditRecord time(Instant time) {
        this.time = time;
        return this;
    }

    AuditRecord id(String id) {
        this.id = id;
        return this;
    }

    AuditRecord requestId(String requestId) {
        this.requestId = requestId;
        return this;
    }

    AuditRecord session(String session) {
        this.session = session;
        return this;
    }

    /**
     * One detail. Values are strings, numbers, booleans or null - anything else is written
     * as its string - strings are cut to {@value #MAX_STRING} characters, and secrets are
     * redacted.
     */
    public AuditRecord detail(String key, Object value) {
        details.put(key, sanitise(key, value));
        return this;
    }

    public AuditRecord details(Map<String, ?> values) {
        values.forEach(this::detail);
        return this;
    }

    /** A change: {@code <field>.old} and {@code <field>.new}. */
    public AuditRecord change(String field, Object oldValue, Object newValue) {
        detail(field + ".old", oldValue);
        detail(field + ".new", newValue);
        return this;
    }

    AuditRecord entity(AuditEvent entity) {
        this.entity = entity;
        return this;
    }

    AuditRecord markContextCaptured() {
        this.contextCaptured = true;
        return this;
    }

    boolean contextCaptured() {
        return contextCaptured;
    }

    public String id() {
        return id;
    }

    public Instant time() {
        return time;
    }

    public Category category() {
        return category;
    }

    public String action() {
        return action;
    }

    public Outcome outcome() {
        return outcome;
    }

    /** As set, or the default for the category and outcome. */
    public Severity severity() {
        if (severity != null) {
            return severity;
        }
        if (outcome != Outcome.SUCCESS) {
            return Severity.WARNING;
        }
        return switch (category) {
            case CONFIGURATION, LICENSE, ACCOUNT -> Severity.NOTICE;
            default -> Severity.INFO;
        };
    }

    public Actor actor() {
        return actor == null ? Actor.system(null) : actor;
    }

    boolean hasActor() {
        return actor != null;
    }

    public Source source() {
        return source;
    }

    public Target target() {
        return target;
    }

    public String reason() {
        return reason;
    }

    public Map<String, Object> details() {
        return Collections.unmodifiableMap(details);
    }

    public String requestId() {
        return requestId;
    }

    public String session() {
        return session;
    }

    AuditEvent entity() {
        return entity;
    }

    /** Whether a detail of this name would be redacted. */
    public static boolean isSecretName(String key) {
        if (key == null) {
            return false;
        }
        String compact = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (compact.contains("password") || compact.contains("apikey") || compact.contains("privatekey")
                || compact.contains("sessionid") || compact.contains("secret") || compact.contains("tokencode")
                || compact.contains("licensetext") || compact.contains("licensefile")
                || compact.endsWith("emergencycode") || compact.contains("onetimecode") || compact.contains("otpcode")
                || compact.contains("resetcode") || compact.contains("recoverycode") || compact.contains("backupcode")
                || compact.contains("verificationcode") || compact.contains("authcode") || compact.equals("code")) {
            return true;
        }
        for (String word : WORD_BOUNDARY.split(key)) {
            if (SECRET_WORDS.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static Object sanitise(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (isSecretName(key)) {
            return REDACTED;
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value;
        }
        return clip(redactValue(value.toString()));
    }

    /** A value that is itself a token, wherever it turns up. */
    private static String redactValue(String value) {
        for (String prefix : TOKEN_PREFIXES) {
            if (value.contains(prefix)) {
                value = value.replaceAll(Pattern.quote(prefix) + "[A-Za-z0-9_-]{20,}", REDACTED);
            }
        }
        return value;
    }

    private static String clip(String value) {
        return value == null || value.length() <= MAX_STRING ? value : value.substring(0, MAX_STRING);
    }
}

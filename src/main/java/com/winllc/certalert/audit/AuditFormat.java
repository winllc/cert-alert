package com.winllc.certalert.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The {@code ens-audit/1} wire format, shared by every ENS product, and its integrity chain.
 *
 * <p>Serialised by hand rather than through a JSON library, because the chain depends on the
 * bytes: fields always in the order the format lists them, none omitted ({@code null} where
 * one does not apply), compact, with nothing a library's configuration could change.
 *
 * <ul>
 *   <li>{@code body} is the event without its {@code hash}, ending {@code "prevHash":"..."}}.
 *   <li>{@code hash = hex(SHA-256(UTF-8(prevHash + "\n" + body)))}.
 *   <li>The line is the body with its final {@code }} replaced by {@code ,"hash":"<hash>"}}.
 * </ul>
 */
public final class AuditFormat {

    public static final String SCHEMA = "ens-audit/1";

    /** The prevHash of seq 1. */
    public static final String GENESIS = "0".repeat(64);

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Length of {@code ,"hash":"<64 hex>"}} - what a line has that its body does not. */
    private static final int HASH_SUFFIX = ",\"hash\":\"".length() + 64 + "\"}".length();

    private AuditFormat() {}

    /** Where the event came from: the same for every event an installation writes. */
    public record Origin(String product, String version, String host, String installationId) {}

    /** The body: the event in field order, without its hash. */
    public static String body(long seq, AuditRecord event, Origin origin, String prevHash) {
        StringBuilder json = new StringBuilder(512);
        json.append('{');
        field(json, "schema").append(string(SCHEMA)).append(',');
        field(json, "id").append(string(event.id())).append(',');
        field(json, "seq").append(seq).append(',');
        field(json, "time").append(string(time(event.time()))).append(',');
        field(json, "product").append(string(origin.product())).append(',');
        field(json, "version").append(string(origin.version())).append(',');
        field(json, "host").append(string(origin.host())).append(',');
        field(json, "installationId").append(string(origin.installationId())).append(',');
        field(json, "category").append(string(event.category().id())).append(',');
        field(json, "action").append(string(event.action())).append(',');
        field(json, "outcome").append(string(event.outcome().id())).append(',');
        field(json, "severity").append(string(event.severity().id())).append(',');

        AuditRecord.Actor actor = event.actor();
        field(json, "actor").append('{');
        field(json, "type").append(string(actor.type().id())).append(',');
        field(json, "id").append(string(actor.id())).append(',');
        field(json, "name").append(string(actor.name())).append(',');
        field(json, "roles").append('[');
        List<String> roles = actor.roles();
        for (int i = 0; i < roles.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(string(roles.get(i)));
        }
        json.append("]},");

        AuditRecord.Source source = event.source();
        field(json, "source");
        if (source == null) {
            json.append("null");
        } else {
            json.append('{');
            field(json, "ip").append(string(source.ip())).append(',');
            field(json, "userAgent").append(string(source.userAgent()));
            json.append('}');
        }
        json.append(',');

        AuditRecord.Target target = event.target();
        field(json, "target");
        if (target == null) {
            json.append("null");
        } else {
            json.append('{');
            field(json, "type").append(string(target.type())).append(',');
            field(json, "id").append(string(target.id())).append(',');
            field(json, "name").append(string(target.name()));
            json.append('}');
        }
        json.append(',');

        field(json, "reason").append(string(event.reason())).append(',');
        field(json, "details").append('{');
        boolean first = true;
        for (Map.Entry<String, Object> detail : event.details().entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(string(detail.getKey())).append(':').append(value(detail.getValue()));
        }
        json.append("},");
        field(json, "requestId").append(string(event.requestId())).append(',');
        field(json, "session").append(string(event.session())).append(',');
        field(json, "prevHash").append(string(prevHash));
        json.append('}');
        return json.toString();
    }

    /** {@code hex(SHA-256(UTF-8(prevHash + "\n" + body)))}. */
    public static String hash(String prevHash, String body) {
        return sha256Hex(prevHash + "\n" + body);
    }

    /** The emitted and stored line: the body with its hash added as the last field. */
    public static String line(String body, String hash) {
        return body.substring(0, body.length() - 1) + ",\"hash\":\"" + hash + "\"}";
    }

    /** The body a stored line was made from. */
    public static String bodyOf(String line) {
        return line.substring(0, line.length() - HASH_SUFFIX) + "}";
    }

    /** The hash a stored line states. */
    public static String hashOf(String line) {
        return line.substring(line.length() - 66, line.length() - 2);
    }

    /** The prevHash a body states: always its last field. */
    public static String prevHashOf(String body) {
        return body.substring(body.length() - 66, body.length() - 2);
    }

    public static String time(Instant instant) {
        return TIME.format(instant.truncatedTo(ChronoUnit.MILLIS));
    }

    public static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static StringBuilder field(StringBuilder json, String name) {
        return json.append('"').append(name).append("\":");
    }

    static String value(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            return value.toString();
        }
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return "null";
            }
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                return Long.toString((long) d);
            }
            return Double.toString(d);
        }
        return string(value.toString());
    }

    /** A JSON string literal: RFC 8259 escaping, everything else passed through as UTF-8. */
    public static String string(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}

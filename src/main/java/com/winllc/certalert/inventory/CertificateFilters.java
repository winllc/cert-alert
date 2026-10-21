package com.winllc.certalert.inventory;

import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.RevocationStatus;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriUtils;

/**
 * A certificate filter written down as the page's query string - which is how a saved view
 * and a scheduled report keep one - and read back. Reading it checks every value, so what is
 * stored is only ever a filter: an unknown key, a status that is not one or a date that is
 * not a date is refused when it is saved, not discovered when a report runs at 3 a.m.
 */
public final class CertificateFilters {

    /** The keys a filter has, and the page's own (sort) that may ride along. */
    public static final Set<String> KEYS = Set.of(
            "q", "status", "ownerType", "expiresWithinDays", "expiresFrom", "expiresTo", "issuer", "keyAlgorithm",
            "keySizeMin", "keySizeMax", "hashAlgorithm", "revocation", "risk", "projectId", "serverId", "userId", "sort");

    public static final int MAX_LENGTH = 2000;

    private CertificateFilters() {}

    /** A filter and the order that went with it. */
    public record Parsed(CertificateFilter filter, String sort) {}

    public static Parsed parse(String query) {
        String text = query == null ? "" : query.trim();
        if (text.startsWith("?")) {
            text = text.substring(1);
        }
        if (text.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("A filter is at most " + MAX_LENGTH + " characters");
        }
        MultiValueMap<String, String> values = new LinkedMultiValueMap<>();
        for (String pair : text.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String key = decode(equals < 0 ? pair : pair.substring(0, equals));
            String value = equals < 0 ? "" : decode(pair.substring(equals + 1));
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("'" + key + "' is not a certificate filter");
            }
            if (!value.isBlank()) {
                values.add(key, value.trim());
            }
        }
        String sort = values.getFirst("sort");
        if (sort != null) {
            CertificateInventory.sort(sort);
        }
        CertificateFilter filter = new CertificateFilter(
                values.getFirst("q"),
                all(values, "status", v -> CertificateStatus.valueOf(v.toUpperCase(Locale.ROOT))),
                one(values, "ownerType", v -> OwnerType.valueOf(v.toUpperCase(Locale.ROOT))),
                one(values, "expiresWithinDays", Integer::valueOf),
                one(values, "expiresFrom", LocalDate::parse),
                one(values, "expiresTo", LocalDate::parse),
                values.getFirst("issuer"),
                values.get("keyAlgorithm"),
                one(values, "keySizeMin", Integer::valueOf),
                one(values, "keySizeMax", Integer::valueOf),
                values.get("hashAlgorithm"),
                all(values, "revocation", v -> RevocationStatus.valueOf(v.toUpperCase(Locale.ROOT))),
                values.getFirst("risk"),
                one(values, "projectId", Long::valueOf),
                one(values, "serverId", Long::valueOf),
                one(values, "userId", Long::valueOf));
        return new Parsed(filter, sort);
    }

    private static String decode(String value) {
        try {
            return UriUtils.decode(value.replace('+', ' '), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("A filter that is not a query string");
        }
    }

    private static <T> T one(MultiValueMap<String, String> values, String key, Function<String, T> read) {
        String value = values.getFirst(key);
        if (value == null) {
            return null;
        }
        try {
            return read.apply(value);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new IllegalArgumentException("'" + value + "' is not a value " + key + " can take");
        }
    }

    private static <T> List<T> all(MultiValueMap<String, String> values, String key, Function<String, T> read) {
        List<String> given = values.get(key);
        if (given == null) {
            return null;
        }
        List<T> read1 = new ArrayList<>();
        for (String value : given) {
            for (String part : value.split(",")) {
                if (part.isBlank()) {
                    continue;
                }
                try {
                    read1.add(read.apply(part.trim()));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("'" + part + "' is not a value " + key + " can take");
                }
            }
        }
        return read1;
    }
}

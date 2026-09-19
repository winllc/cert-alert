package com.winllc.certalert.ldap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads values out of a JNDI {@link Attributes} by configured name.
 *
 * <p>Two directory quirks are handled here. Attribute names are case-insensitive and may
 * carry options, so {@code userCertificate} has to match an attribute the server returned
 * as {@code userCertificate;binary}. And a certificate value arrives as a {@code byte[]}
 * when the provider knows the syntax is binary, but as a {@code String} otherwise, in
 * which case it may be base64.
 */
public final class LdapAttributes {

    private static final Logger log = LoggerFactory.getLogger(LdapAttributes.class);

    private static final String BINARY_OPTION = ";binary";

    private LdapAttributes() {}

    /** First value of the named attribute as a string, or null when absent or empty. */
    public static String string(Attributes attributes, String name) throws NamingException {
        Attribute attribute = find(attributes, name);
        if (attribute == null || attribute.size() == 0) {
            return null;
        }
        Object value = attribute.get();
        if (value == null) {
            return null;
        }
        String text = value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
        return text.isBlank() ? null : text.trim();
    }

    /** All values of the named attribute as strings, preserving directory order. */
    public static Set<String> strings(Attributes attributes, String name) throws NamingException {
        Attribute attribute = find(attributes, name);
        Set<String> values = new LinkedHashSet<>();
        if (attribute == null) {
            return values;
        }
        NamingEnumeration<?> all = attribute.getAll();
        while (all.hasMore()) {
            Object value = all.next();
            if (value == null) {
                continue;
            }
            String text = value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : value.toString();
            if (!text.isBlank()) {
                values.add(text.trim());
            }
        }
        return values;
    }

    /** All values of the named attribute as DER byte arrays. */
    static List<byte[]> binaries(Attributes attributes, String name) throws NamingException {
        Attribute attribute = find(attributes, name);
        List<byte[]> values = new ArrayList<>();
        if (attribute == null) {
            return values;
        }
        NamingEnumeration<?> all = attribute.getAll();
        while (all.hasMore()) {
            Object value = all.next();
            if (value instanceof byte[] bytes) {
                if (bytes.length > 0) {
                    values.add(bytes);
                }
            } else if (value instanceof String text && !text.isBlank()) {
                decodeText(text, name).ifPresent(values::add);
            }
        }
        return values;
    }

    /**
     * A string-valued certificate is either base64 (how LDIF carries it) or raw bytes the
     * provider widened to a String. Base64 is tried first; the fallback preserves every
     * byte because ISO-8859-1 maps 0-255 one to one.
     */
    private static java.util.Optional<byte[]> decodeText(String text, String name) {
        try {
            return java.util.Optional.of(Base64.getMimeDecoder().decode(text));
        } catch (IllegalArgumentException e) {
            log.debug("Attribute '{}' was not base64, falling back to raw bytes", name);
            return java.util.Optional.of(text.getBytes(StandardCharsets.ISO_8859_1));
        }
    }

    /**
     * Finds an attribute by name, ignoring case and any attribute options the server
     * appended, so a request for {@code userCertificate} still matches
     * {@code userCertificate;binary}.
     */
    private static Attribute find(Attributes attributes, String name) throws NamingException {
        if (name == null || name.isBlank()) {
            return null;
        }
        Attribute direct = attributes.get(name);
        if (direct != null) {
            return direct;
        }
        String wanted = baseName(name);
        NamingEnumeration<String> ids = attributes.getIDs();
        while (ids.hasMore()) {
            String id = ids.next();
            if (baseName(id).equalsIgnoreCase(wanted)) {
                return attributes.get(id);
            }
        }
        return null;
    }

    private static String baseName(String name) {
        int option = name.indexOf(';');
        return option < 0 ? name : name.substring(0, option);
    }

    /** The name to request from the server, asking for binary transfer explicitly. */
    static String asBinaryRequest(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(BINARY_OPTION) ? name : name + BINARY_OPTION;
    }
}

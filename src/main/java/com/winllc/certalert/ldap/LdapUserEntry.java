package com.winllc.certalert.ldap;

import java.util.List;
import java.util.Map;

/**
 * One IC Person as read from the directory, before it is reconciled into the database.
 *
 * @param extraEmails addresses from the attributes a deployment named in
 *     {@code additional-email-attributes}, keyed by the attribute they came from so that
 *     {@code email-precedence} can name one of them as the primary address. Empty where
 *     none are configured, which is the default.
 */
public record LdapUserEntry(
        String dn,
        Map<UserField, String> values,
        Map<String, List<String>> extraEmails,
        List<byte[]> certificates) {

    public String get(UserField field) {
        return values.get(field);
    }

    /** Everything the extra attributes hold, in the order the attributes were listed. */
    public List<String> allExtraEmails() {
        return extraEmails.values().stream().flatMap(List::stream).distinct().toList();
    }

    /** What one named attribute holds, or nothing where it holds nothing usable. */
    public List<String> extraEmails(String attribute) {
        return extraEmails.getOrDefault(attribute, List.of());
    }
}

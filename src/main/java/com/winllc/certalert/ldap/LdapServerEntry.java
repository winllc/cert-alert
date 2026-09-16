package com.winllc.certalert.ldap;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One IC Non-Person Entity as read from the directory.
 *
 * @param serverPocs the {@code serverPOC} values, kept apart from the rest because they
 *     are the join to the people responsible for this server
 */
public record LdapServerEntry(
        String dn, Map<ServerField, String> values, Set<String> serverPocs, List<byte[]> certificates) {

    public String get(ServerField field) {
        return values.get(field);
    }
}

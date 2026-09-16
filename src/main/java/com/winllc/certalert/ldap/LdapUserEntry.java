package com.winllc.certalert.ldap;

import java.util.List;
import java.util.Map;

/** One IC Person as read from the directory, before it is reconciled into the database. */
public record LdapUserEntry(String dn, Map<UserField, String> values, List<byte[]> certificates) {

    public String get(UserField field) {
        return values.get(field);
    }
}

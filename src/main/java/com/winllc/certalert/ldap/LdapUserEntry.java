package com.winllc.certalert.ldap;

import java.util.List;

/** One person as read from the directory, before it is reconciled into the database. */
public record LdapUserEntry(
        String dn,
        String uid,
        String commonName,
        String displayName,
        String givenName,
        String surname,
        String email,
        String telephoneNumber,
        String title,
        String employeeType,
        String country,
        String organization,
        String organizationalUnit,
        List<byte[]> certificates) {}

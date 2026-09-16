package com.winllc.certalert.ldap;

import java.util.List;
import java.util.Set;

/** One server as read from the directory, before it is reconciled into the database. */
public record LdapServerEntry(
        String dn,
        String commonName,
        String fqdn,
        String description,
        String serialNumber,
        String operatingSystem,
        Set<String> serverPocs,
        String organization,
        String organizationalUnit,
        List<byte[]> certificates) {}

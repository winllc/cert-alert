package com.winllc.certalert.support;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.Modification;
import com.unboundid.ldap.sdk.ModificationType;
import com.unboundid.ldap.sdk.ModifyRequest;

/**
 * An in-memory LDAP server for the sync tests.
 *
 * <p>Schema checking is switched off deliberately. The point of these tests is the
 * scraping and reconciliation logic, and a real IdAM directory carries attributes such as
 * {@code serverPoc} that no stock schema defines; loading a schema here would test the
 * fixture rather than the code.
 */
public final class EmbeddedDirectory implements AutoCloseable {

    public static final String BASE_DN = "dc=example,dc=test";
    public static final String PEOPLE_DN = "ou=people," + BASE_DN;
    public static final String SERVERS_DN = "ou=servers," + BASE_DN;

    private final InMemoryDirectoryServer server;

    public EmbeddedDirectory() {
        try {
            InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
            config.setSchema(null);
            config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("test", 0));
            this.server = new InMemoryDirectoryServer(config);
            this.server.startListening();
            seedStructure();
        } catch (LDAPException e) {
            throw new IllegalStateException("Could not start the in-memory directory", e);
        }
    }

    public String url() {
        return "ldap://localhost:" + server.getListenPort();
    }

    private void seedStructure() throws LDAPException {
        server.add(new Entry(BASE_DN, new Attribute("objectClass", "top", "domain"), new Attribute("dc", "example")));
        server.add(new Entry(
                PEOPLE_DN,
                new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "people")));
        server.add(new Entry(
                SERVERS_DN,
                new Attribute("objectClass", "top", "organizationalUnit"),
                new Attribute("ou", "servers")));
    }

    /** Adds a person. Certificates are stored under the binary attribute option. */
    public String addUser(String uid, String displayName, String email, byte[]... certificates) {
        String dn = "uid=" + uid + "," + PEOPLE_DN;
        Entry entry = new Entry(
                dn,
                new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson"),
                new Attribute("uid", uid),
                new Attribute("cn", displayName),
                new Attribute("sn", displayName.substring(displayName.lastIndexOf(' ') + 1)),
                new Attribute("displayName", displayName),
                new Attribute("mail", email),
                new Attribute("o", "Example Agency"),
                new Attribute("ou", "people"));
        if (certificates.length > 0) {
            entry.addAttribute(new Attribute("userCertificate;binary", certificates));
        }
        add(entry);
        return dn;
    }

    /** Adds a server. {@code serverPoc} is multi-valued, as it is in the real directory. */
    public String addServer(String cn, String fqdn, String[] pocs, byte[]... certificates) {
        String dn = "cn=" + cn + "," + SERVERS_DN;
        Entry entry = new Entry(
                dn,
                new Attribute("objectClass", "top", "device"),
                new Attribute("cn", cn),
                new Attribute("associatedDomain", fqdn),
                new Attribute("description", cn + " host"),
                new Attribute("operatingSystem", "Linux"),
                new Attribute("o", "Example Agency"),
                new Attribute("ou", "servers"));
        if (pocs.length > 0) {
            entry.addAttribute(new Attribute("serverPoc", pocs));
        }
        if (certificates.length > 0) {
            entry.addAttribute(new Attribute("userCertificate;binary", certificates));
        }
        add(entry);
        return dn;
    }

    public void delete(String dn) {
        try {
            server.delete(dn);
        } catch (LDAPException e) {
            throw new IllegalStateException("Could not delete " + dn, e);
        }
    }

    /** Replaces an entry's certificates, the way a directory does when one is rotated. */
    public void replaceCertificates(String dn, byte[]... certificates) {
        try {
            server.modify(new ModifyRequest(
                    dn,
                    new Modification(ModificationType.REPLACE, "userCertificate;binary", certificates)));
        } catch (LDAPException e) {
            throw new IllegalStateException("Could not replace certificates on " + dn, e);
        }
    }

    private void add(Entry entry) {
        try {
            server.add(entry);
        } catch (LDAPException e) {
            throw new IllegalStateException("Could not add " + entry.getDN(), e);
        }
    }

    @Override
    public void close() {
        server.shutDown(true);
    }
}

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
import com.unboundid.ldif.LDIFException;
import com.unboundid.ldif.LDIFReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * An in-memory LDAP server holding IC FSD shaped entries.
 *
 * <p>Schema checking is switched off deliberately. The point of these tests is the
 * scraping and reconciliation logic, and the IC FSD attributes ({@code serverPOC},
 * {@code icEmail}, {@code ATOStatus} and the rest) are IC-defined; loading a schema here
 * would test the fixture rather than the code.
 */
public final class EmbeddedDirectory implements AutoCloseable {

    public static final String BASE_DN = "dc=example,dc=test";
    public static final String PEOPLE_DN = "ou=people," + BASE_DN;
    public static final String SERVERS_DN = "ou=servers," + BASE_DN;

    /** The changelog is its own suffix, as it is in a real directory. */
    public static final String CHANGELOG_DN = "cn=changelog";

    private final InMemoryDirectoryServer server;

    public EmbeddedDirectory() {
        this(0);
    }

    /**
     * @param maxChangeLogEntries how many changes the directory retains, or 0 for no
     *     changelog at all. The server records changes itself, at {@code cn=changelog},
     *     following the same draft-good-ldap-changelog shape the connector reads - so the
     *     tests run against a real changelog rather than a hand-written imitation. A small
     *     number makes it trim, which is how a gap is produced on purpose.
     */
    public EmbeddedDirectory(int maxChangeLogEntries) {
        try {
            InMemoryDirectoryServerConfig config = new InMemoryDirectoryServerConfig(BASE_DN);
            config.setSchema(null);
            config.setMaxChangeLogEntries(maxChangeLogEntries);
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

    public int entryCount() {
        return server.countEntries();
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

    /**
     * Loads an LDIF file, skipping any entry the structure already holds. Used to run the
     * scrape over what scripts/generate-directory-data.sh produces.
     */
    public void importLdif(Path ldif) {
        try (LDIFReader reader = new LDIFReader(ldif.toFile())) {
            Entry entry;
            while ((entry = reader.readEntry()) != null) {
                if (server.getEntry(entry.getDN()) == null) {
                    server.add(entry);
                }
            }
        } catch (IOException | LDIFException | LDAPException e) {
            throw new IllegalStateException("Could not load " + ldif, e);
        }
    }

    /** Replaces a single attribute, as an ordinary directory modify would. */
    public void modify(String dn, String attribute, String... values) {
        try {
            server.modify(new ModifyRequest(dn, new Modification(ModificationType.REPLACE, attribute, values)));
        } catch (LDAPException e) {
            throw new IllegalStateException("Could not modify " + dn, e);
        }
    }

    /** Renames an entry, as a modrdn would. */
    public void rename(String dn, String newRdn) {
        try {
            server.modifyDN(dn, newRdn, true);
        } catch (LDAPException e) {
            throw new IllegalStateException("Could not rename " + dn, e);
        }
    }

    /** Adds an IC Person. Certificates are stored under the binary attribute option. */
    public String addUser(String uid, String displayName, String icEmail, byte[]... certificates) {
        add(userEntry(uid, displayName, icEmail, certificates));
        return "uid=" + uid + "," + PEOPLE_DN;
    }

    /** Adds an IC Non-Person Entity. {@code serverPOC} names the responsible person. */
    public String addServer(String cn, String serverUrl, String[] pocs, byte[]... certificates) {
        add(serverEntry(cn, serverUrl, pocs, certificates));
        return "cn=" + cn + "," + SERVERS_DN;
    }

    /**
     * Adds many people in one go, for exercising the paged and batched scrape. Every
     * tenth one carries a certificate, so the cache has something to reconcile without
     * the fixture costing thousands of signatures.
     */
    public void addUsers(int count, byte[] sharedCertificate) {
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[][] certificates = i % 10 == 0 ? new byte[][] {sharedCertificate} : new byte[0][];
            entries.add(userEntry("bulk%05d".formatted(i), "Bulk Person %05d".formatted(i),
                    "bulk%05d@example.gov".formatted(i), certificates));
        }
        entries.forEach(this::add);
    }

    /** Adds many servers, every fifth one pointing at a person added by {@link #addUsers}. */
    public void addServers(int count, byte[] sharedCertificate) {
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[][] certificates = i % 10 == 0 ? new byte[][] {sharedCertificate} : new byte[0][];
            String[] pocs = i % 5 == 0
                    ? new String[] {"bulk%05d@example.gov".formatted(i)}
                    : new String[] {"ops@example.gov"};
            entries.add(serverEntry("bulksrv%05d".formatted(i), "https://bulk%05d.example.gov".formatted(i),
                    pocs, certificates));
        }
        entries.forEach(this::add);
    }

    private Entry userEntry(String uid, String displayName, String icEmail, byte[]... certificates) {
        Entry entry = new Entry(
                "uid=" + uid + "," + PEOPLE_DN,
                new Attribute("objectClass", "top", "person", "organizationalPerson", "inetOrgPerson", "icOrgPerson"),
                new Attribute("uid", uid),
                // Lets the tests exercise a real bind against this entry.
                new Attribute("userPassword", "password"),
                new Attribute("cn", displayName),
                new Attribute("sn", displayName.substring(displayName.lastIndexOf(' ') + 1)),
                new Attribute("givenName", displayName.substring(0, displayName.indexOf(' '))),
                new Attribute("displayName", displayName),
                new Attribute("icEmail", icEmail),
                new Attribute("employeeType", "Civilian"),
                new Attribute("countryOfAffiliation", "USA"),
                new Attribute("dutyOrganization", "Example Agency"),
                new Attribute("adminOrganization", "Example Agency"),
                new Attribute("isICMember", "TRUE"),
                new Attribute("icNetworks", "JWICS"),
                new Attribute("resourceSecurityMark", "UNCLASSIFIED"),
                new Attribute("o", "Example Agency"),
                new Attribute("ou", "people"));
        if (certificates.length > 0) {
            entry.addAttribute(new Attribute("userCertificate;binary", certificates));
        }
        return entry;
    }

    private Entry serverEntry(String cn, String serverUrl, String[] pocs, byte[]... certificates) {
        Entry entry = new Entry(
                "cn=" + cn + "," + SERVERS_DN,
                new Attribute("objectClass", "top", "icOrgServer"),
                new Attribute("cn", cn),
                new Attribute("uid", cn),
                new Attribute("givenName", cn),
                new Attribute("serverURL", serverUrl),
                new Attribute("icServerAddress", "10.1.2.3"),
                new Attribute("description", cn + " application host"),
                new Attribute("ATOStatus", "Authorized"),
                new Attribute("lifeCycleStatus", "Production"),
                new Attribute("employeeType", "NPE"),
                new Attribute("countryOfAffiliation", "USA"),
                new Attribute("dutyOrganization", "Example Agency"),
                new Attribute("adminOrganization", "Example Agency"),
                new Attribute("isICMember", "TRUE"),
                new Attribute("icNetworks", "JWICS"),
                new Attribute("resourceSecurityMark", "UNCLASSIFIED"),
                new Attribute("o", "Example Agency"),
                new Attribute("ou", "servers"));
        if (pocs.length > 0) {
            entry.addAttribute(new Attribute("serverPOC", pocs));
        }
        if (certificates.length > 0) {
            entry.addAttribute(new Attribute("userCertificate;binary", certificates));
        }
        return entry;
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
                    dn, new Modification(ModificationType.REPLACE, "userCertificate;binary", certificates)));
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

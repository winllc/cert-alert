package com.winllc.certalert.ldap;

import java.util.List;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchControls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads and removes the certificates an entry publishes, by distinguished name.
 *
 * <p>Removing one means naming the exact value to delete, and a value here is the DER of a
 * certificate - so the bytes have to be in hand, and the cache holds a fingerprint rather
 * than the bytes. That is why this reads first: the entry's certificates come back, the one
 * with the matching fingerprint is picked out, and that value is deleted. One extra search
 * per entry, on a job that runs weekly and removes what it finds.
 *
 * <p>Deliberately narrow. It deletes values from an attribute and does nothing else: it
 * cannot add one, and it cannot replace the attribute wholesale, which on a multi-valued
 * attribute is the difference between removing a certificate and removing all of them.
 */
@Component
public class LdapCertificateStore {

    private static final Logger log = LoggerFactory.getLogger(LdapCertificateStore.class);

    private final LdapTemplate template;

    public LdapCertificateStore(DirectoryEntryConnection connection) {
        this.template = connection.template();
    }

    /**
     * The certificates this entry publishes, as the directory holds them.
     *
     * @param attribute the attribute they live in, unqualified - the binary option is
     *     added here, because without it a directory hands back a string
     */
    public List<byte[]> read(String dn, String attribute) {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.OBJECT_SCOPE);
        controls.setReturningAttributes(new String[] {LdapAttributes.asBinaryRequest(attribute)});
        controls.setCountLimit(1);

        List<List<byte[]>> found = template.search(dn, "(objectClass=*)", controls,
                (ContextMapper<List<byte[]>>) ctx -> {
                    try {
                        Attributes attributes = ((DirContextOperations) ctx).getAttributes();
                        return LdapAttributes.binaries(attributes, attribute);
                    } catch (NamingException e) {
                        throw new DirectoryReadException("Failed to read certificates from " + dn, e);
                    }
                });
        return found.isEmpty() ? List.of() : found.getFirst();
    }

    /**
     * Deletes these exact values from the entry.
     *
     * <p>One modification naming the values, rather than one per value: a directory applies
     * a modify atomically, so either all of them go or none does, and an entry is never
     * left half cleaned up because the connection dropped in the middle.
     *
     * @throws org.springframework.ldap.NamingException if the directory refuses - its
     *     access control has the last word, and being refused is the ordinary outcome
     *     where this application has not been granted write on the attribute
     */
    public void remove(String dn, String attribute, List<byte[]> values) {
        if (values.isEmpty()) {
            return;
        }
        BasicAttribute removing = new BasicAttribute(LdapAttributes.asBinaryRequest(attribute));
        for (byte[] value : values) {
            removing.add(value);
        }
        ModificationItem[] modifications = {
            new ModificationItem(DirContext.REMOVE_ATTRIBUTE, removing)
        };
        log.debug("Removing {} certificate value(s) from '{}'", values.size(), dn);
        template.modifyAttributes(dn, modifications);
    }
}

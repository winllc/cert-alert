package com.winllc.certalert.ldap;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.stereotype.Component;

/**
 * Turns a directory entry into the record the rest of the application works with.
 *
 * <p>Shared by the full sweep and by the changelog connector re-reading a single changed
 * entry, so both produce exactly the same thing: an incremental update can never drift
 * from what a sweep would have written.
 */
@Component
public class DirectoryEntryMapper {

    private final LdapProperties properties;

    public DirectoryEntryMapper(LdapProperties properties) {
        this.properties = properties;
    }

    /** Attributes to request for a person, the certificate among them. */
    public String[] userAttributes() {
        LdapProperties.User mapping = properties.getUser();
        List<String> attributes = new ArrayList<>();
        for (UserField field : UserField.values()) {
            addIfPresent(attributes, field.attributeName(mapping));
        }
        addIfPresent(attributes, requestedCertificate(mapping.getCertificate()));
        return attributes.toArray(String[]::new);
    }

    /** Attributes to request for an IC Non-Person Entity. */
    public String[] serverAttributes() {
        LdapProperties.Server mapping = properties.getServer();
        List<String> attributes = new ArrayList<>();
        for (ServerField field : ServerField.values()) {
            addIfPresent(attributes, field.attributeName(mapping));
        }
        addIfPresent(attributes, mapping.getServerPoc());
        addIfPresent(attributes, requestedCertificate(mapping.getCertificate()));
        return attributes.toArray(String[]::new);
    }

    /** How this directory wants the certificate attribute named - see the toggle's javadoc. */
    private String requestedCertificate(String attribute) {
        return LdapAttributes.requestName(attribute, properties.isBinaryCertificateOption());
    }

    public LdapUserEntry toUser(DirContextOperations ctx) throws NamingException {
        LdapProperties.User mapping = properties.getUser();
        Attributes source = ctx.getAttributes();
        Map<UserField, String> values = new EnumMap<>(UserField.class);
        for (UserField field : UserField.values()) {
            String value = LdapAttributes.string(source, field.attributeName(mapping));
            if (value != null) {
                values.put(field, value);
            }
        }
        return new LdapUserEntry(
                ctx.getNameInNamespace(), values, LdapAttributes.binaries(source, mapping.getCertificate()));
    }

    public LdapServerEntry toServer(DirContextOperations ctx) throws NamingException {
        LdapProperties.Server mapping = properties.getServer();
        Attributes source = ctx.getAttributes();
        Map<ServerField, String> values = new EnumMap<>(ServerField.class);
        for (ServerField field : ServerField.values()) {
            String value = LdapAttributes.string(source, field.attributeName(mapping));
            if (value != null) {
                values.put(field, value);
            }
        }
        Set<String> pocs = LdapAttributes.strings(source, mapping.getServerPoc());
        return new LdapServerEntry(
                ctx.getNameInNamespace(), values, pocs, LdapAttributes.binaries(source, mapping.getCertificate()));
    }

    private void addIfPresent(List<String> attributes, String name) {
        if (name != null && !name.isBlank() && !attributes.contains(name)) {
            attributes.add(name);
        }
    }
}

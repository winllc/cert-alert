package com.winllc.certalert.ldap;

import com.winllc.certalert.domain.EmailAddresses;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import org.springframework.ldap.core.DirContextOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(DirectoryEntryMapper.class);

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
        // Whatever else this deployment keeps addresses in. Asked for by name, since there
        // is no schema to look them up in - that is the point of the setting.
        for (String attribute : mapping.getAdditionalEmailAttributes()) {
            addIfPresent(attributes, attribute);
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
        String dn = ctx.getNameInNamespace();
        return new LdapUserEntry(
                dn,
                values,
                extraEmails(source, mapping, dn),
                LdapAttributes.binaries(source, mapping.getCertificate()));
    }

    /**
     * Addresses out of the attributes a deployment named for itself.
     *
     * <p>Read as multi-valued and split on commas, for the same reason {@code serverPOC} is:
     * an attribute can hold several values, and a directory filled in through a form often
     * holds several addresses inside one of them.
     *
     * <p>Anything that is not an address is dropped. These attributes were named as places
     * addresses live, so a display name in one is bad data rather than a second kind of
     * value to guess at - and an identifier that is not an address would silently widen
     * what a {@code serverPOC} matches. It is logged with the entry it came from, so it can
     * be fixed where it lives.
     */
    private Map<String, List<String>> extraEmails(
            Attributes source, LdapProperties.User mapping, String dn) throws NamingException {

        List<String> configured = mapping.getAdditionalEmailAttributes();
        if (configured.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> found = new LinkedHashMap<>();
        List<String> rejected = new ArrayList<>();
        for (String attribute : configured) {
            if (attribute == null || attribute.isBlank()) {
                continue;
            }
            List<String> addresses = new ArrayList<>();
            for (String value : LdapAttributes.strings(source, attribute.trim())) {
                for (String candidate : EmailAddresses.split(value)) {
                    if (EmailAddresses.isAddress(candidate)) {
                        String normalised = EmailAddresses.normalise(candidate);
                        if (!addresses.contains(normalised)) {
                            addresses.add(normalised);
                        }
                    } else {
                        rejected.add(attribute.trim() + "=" + candidate);
                    }
                }
            }
            if (!addresses.isEmpty()) {
                found.put(attribute.trim(), List.copyOf(addresses));
            }
        }
        if (!rejected.isEmpty()) {
            log.warn("'{}' holds {} value(s) in its email attribute(s) that are not addresses and were "
                    + "ignored: {}", dn, rejected.size(), String.join(", ", rejected));
        }
        return Map.copyOf(found);
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
        String dn = ctx.getNameInNamespace();
        Set<String> pocs = pointsOfContact(LdapAttributes.strings(source, mapping.getServerPoc()), mapping, dn);
        return new LdapServerEntry(dn, values, pocs, LdapAttributes.binaries(source, mapping.getCertificate()));
    }

    /**
     * The contacts a server's {@code serverPOC} names.
     *
     * <p>An attribute is multi-valued, but a directory that was filled in through a form
     * often carries one value with several addresses comma-separated inside it. Read
     * whole, such a value matches nobody: it is not an address and it is not a name either,
     * so the server silently has no contacts and nobody is told when its certificate runs
     * out. Every value is therefore split, which leaves an ordinary single address alone.
     *
     * <p>What survives after that depends on the convention the directory follows - see
     * {@code require-email-poc}. Where values are addresses, anything that is not one is
     * bad data and is dropped rather than cached as a contact nothing can be sent to; it
     * is logged with the entry's name so it can be found and fixed where it lives.
     */
    private Set<String> pointsOfContact(Set<String> values, LdapProperties.Server mapping, String dn) {
        Set<String> pocs = new LinkedHashSet<>();
        List<String> rejected = new ArrayList<>();
        for (String value : values) {
            for (String candidate : EmailAddresses.split(value)) {
                if (mapping.isRequireEmailPoc() && !EmailAddresses.isAddress(candidate)) {
                    rejected.add(candidate);
                    continue;
                }
                pocs.add(candidate);
            }
        }
        if (!rejected.isEmpty()) {
            // One line per entry rather than per value, and at warn because it is the
            // directory that needs correcting. A whole directory of them means the
            // convention is names, and require-email-poc is the wrong way round.
            log.warn("'{}' has {} '{}' value(s) that are not email addresses, ignoring them: {}",
                    dn, rejected.size(), mapping.getServerPoc(), rejected);
        }
        return pocs;
    }

    private void addIfPresent(List<String> attributes, String name) {
        if (name != null && !name.isBlank() && !attributes.contains(name)) {
            attributes.add(name);
        }
    }
}

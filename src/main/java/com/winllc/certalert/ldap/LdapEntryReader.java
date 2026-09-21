package com.winllc.certalert.ldap;

import java.util.List;
import java.util.Optional;
import javax.naming.InvalidNameException;
import javax.naming.NamingException;
import javax.naming.directory.SearchControls;
import javax.naming.ldap.LdapName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ldap.autoconfigure.LdapConnectionDetails;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads one entry back by its distinguished name, and says which of the two object types
 * it is - if either.
 *
 * <p>Uses the base-less connection because a changelog's {@code targetDN} is absolute, and
 * the shared context source would resolve it under {@code spring.ldap.base}.
 *
 * <p>An entry counts as one of ours only if a sweep would have collected it, which takes
 * two tests and not one. The sweep's search filter says what shape the entry has to be;
 * the sweep's search base says where it has to live. Both are applied here.
 *
 * <p>The second used to be missing, and the filter alone is not enough. A changelog covers
 * whatever the directory was told to record, which is usually more of the tree than either
 * sweep reads - so an entry of the right objectClass sitting outside {@code ou=people}, or
 * outside {@code spring.ldap.base} altogether, would be cached by the connector though no
 * sweep would ever see it. The cache then held entries that the sweeps could neither
 * refresh nor account for.
 */
@Component
@ConditionalOnProperty(prefix = "cert-alert.ldap.changelog", name = "enabled", havingValue = "true")
public class LdapEntryReader {

    private static final Logger log = LoggerFactory.getLogger(LdapEntryReader.class);

    private final LdapTemplate ldapTemplate;
    private final LdapProperties properties;
    private final DirectoryEntryMapper mapper;
    private final LdapName userBase;
    private final LdapName serverBase;

    public LdapEntryReader(
            ChangelogConnection connection,
            LdapProperties properties,
            DirectoryEntryMapper mapper,
            LdapConnectionDetails connectionDetails) {
        this.ldapTemplate = connection.template();
        this.properties = properties;
        this.mapper = mapper;
        this.userBase = searchBase(connectionDetails.getBase(), properties.getUser().getSearchBase());
        this.serverBase = searchBase(connectionDetails.getBase(), properties.getServer().getSearchBase());
        log.debug("Changelog will accept people under '{}' and servers under '{}'", userBase, serverBase);
    }

    /** The entry at this DN, if it is an IC Person the sweep would collect. */
    public Optional<LdapUserEntry> readUser(String dn) {
        return readOne(
                dn, userBase, properties.getUser().getSearchFilter(), mapper.userAttributes(), mapper::toUser);
    }

    /** The entry at this DN, if it is an IC Non-Person Entity the sweep would collect. */
    public Optional<LdapServerEntry> readServer(String dn) {
        return readOne(
                dn, serverBase, properties.getServer().getSearchFilter(), mapper.serverAttributes(), mapper::toServer);
    }

    private <T> Optional<T> readOne(
            String dn, LdapName base, String filter, String[] attributes, ThrowingMapper<T> entryMapper) {

        // Asked before the read rather than after: an entry the sweep would never reach is
        // not worth a round trip, whatever it turns out to be.
        if (!within(dn, base)) {
            return Optional.empty();
        }

        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.OBJECT_SCOPE);
        controls.setReturningAttributes(attributes);
        controls.setCountLimit(1);

        List<T> found = ldapTemplate.search(dn, filter, controls, (ContextMapper<T>) ctx -> {
            try {
                return entryMapper.map((DirContextOperations) ctx);
            } catch (NamingException e) {
                throw new DirectoryReadException("Failed to read entry " + dn, e);
            }
        });
        return found.isEmpty() ? Optional.empty() : Optional.ofNullable(found.getFirst());
    }

    /**
     * Whether this name sits under that base.
     *
     * <p>Compared as distinguished names rather than as text, because the two are not the
     * same thing: {@code UID=alice, OU=People, DC=example, DC=test} is the same entry as
     * {@code uid=alice,ou=people,dc=example,dc=test} and no string comparison says so.
     */
    private boolean within(String dn, LdapName base) {
        if (base.isEmpty()) {
            // Nothing configured to be under. The sweep reads the whole tree, so this does.
            return true;
        }
        try {
            if (new LdapName(dn).startsWith(base)) {
                return true;
            }
            log.debug("Ignoring '{}': the sweep reads '{}' and would never see it", dn, base);
            return false;
        } catch (InvalidNameException e) {
            log.warn("The changelog named '{}', which is not a distinguished name; ignoring it", dn);
            return false;
        }
    }

    /**
     * Where a sweep of this type actually looks: its own search base resolved under
     * {@code spring.ldap.base}, which is what the sweep's context source applies to it.
     * Empty when neither is configured, which means the whole tree.
     */
    private static LdapName searchBase(String root, String relative) {
        try {
            LdapName base = new LdapName(root == null ? "" : root.trim());
            if (relative != null && !relative.isBlank()) {
                base.addAll(new LdapName(relative.trim()));
            }
            return base;
        } catch (InvalidNameException e) {
            throw new IllegalStateException(
                    "Cannot read the changelog: '" + relative + "' under '" + root
                            + "' is not a distinguished name",
                    e);
        }
    }

    @FunctionalInterface
    private interface ThrowingMapper<T> {
        T map(DirContextOperations ctx) throws NamingException;
    }
}

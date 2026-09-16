package com.winllc.certalert.ldap;

import java.util.List;
import java.util.Optional;
import javax.naming.NamingException;
import javax.naming.directory.SearchControls;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * <p>Whether an entry is a person or a server is decided by running the same search filters
 * the full sweep uses, at base scope against that one entry. That keeps the two paths
 * honest: anything the sweep would pick up, the connector picks up, whatever shape those
 * filters take.
 */
@Component
@ConditionalOnProperty(prefix = "cert-alert.ldap.changelog", name = "enabled", havingValue = "true")
public class LdapEntryReader {

    private final LdapTemplate ldapTemplate;
    private final LdapProperties properties;
    private final DirectoryEntryMapper mapper;

    public LdapEntryReader(
            ChangelogConnection connection, LdapProperties properties, DirectoryEntryMapper mapper) {
        this.ldapTemplate = connection.template();
        this.properties = properties;
        this.mapper = mapper;
    }

    /** The entry at this DN, if it is an IC Person the sweep would collect. */
    public Optional<LdapUserEntry> readUser(String dn) {
        return readOne(dn, properties.getUser().getSearchFilter(), mapper.userAttributes(), mapper::toUser);
    }

    /** The entry at this DN, if it is an IC Non-Person Entity the sweep would collect. */
    public Optional<LdapServerEntry> readServer(String dn) {
        return readOne(dn, properties.getServer().getSearchFilter(), mapper.serverAttributes(), mapper::toServer);
    }

    private <T> Optional<T> readOne(String dn, String filter, String[] attributes, ThrowingMapper<T> entryMapper) {
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

    @FunctionalInterface
    private interface ThrowingMapper<T> {
        T map(DirContextOperations ctx) throws NamingException;
    }
}

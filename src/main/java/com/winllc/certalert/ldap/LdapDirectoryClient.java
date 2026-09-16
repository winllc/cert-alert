package com.winllc.certalert.ldap;

import java.util.ArrayList;
import java.util.List;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.control.PagedResultsDirContextProcessor;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.ContextSource;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads users and servers out of the directory.
 *
 * <p>Searches are paged by default. A directory of any size enforces an administrative
 * size limit, and an unpaged search simply stops at that limit, silently returning a
 * partial view of the tree - which for this application would mean quietly forgetting
 * certificates.
 */
@Component
public class LdapDirectoryClient {

    private static final Logger log = LoggerFactory.getLogger(LdapDirectoryClient.class);

    private final ContextSource contextSource;
    private final LdapProperties properties;

    public LdapDirectoryClient(ContextSource contextSource, LdapProperties properties) {
        this.contextSource = contextSource;
        this.properties = properties;
    }

    public List<LdapUserEntry> fetchUsers() {
        LdapProperties.User user = properties.getUser();
        String[] attributes = {
            user.getUid(),
            user.getCommonName(),
            user.getDisplayName(),
            user.getGivenName(),
            user.getSurname(),
            user.getEmail(),
            user.getTelephoneNumber(),
            user.getTitle(),
            user.getEmployeeType(),
            user.getCountry(),
            user.getOrganization(),
            user.getOrganizationalUnit(),
            LdapAttributes.asBinaryRequest(user.getCertificate())
        };
        return search(user.getSearchBase(), user.getSearchFilter(), attributes, this::toUser);
    }

    public List<LdapServerEntry> fetchServers() {
        LdapProperties.Server server = properties.getServer();
        String[] attributes = {
            server.getCommonName(),
            server.getFqdn(),
            server.getDescription(),
            server.getSerialNumber(),
            server.getOperatingSystem(),
            server.getServerPoc(),
            server.getOrganization(),
            server.getOrganizationalUnit(),
            LdapAttributes.asBinaryRequest(server.getCertificate())
        };
        return search(server.getSearchBase(), server.getSearchFilter(), attributes, this::toServer);
    }

    private LdapUserEntry toUser(DirContextOperations ctx) throws NamingException {
        LdapProperties.User mapping = properties.getUser();
        Attributes attributes = ctx.getAttributes();
        return new LdapUserEntry(
                ctx.getNameInNamespace(),
                LdapAttributes.string(attributes, mapping.getUid()),
                LdapAttributes.string(attributes, mapping.getCommonName()),
                LdapAttributes.string(attributes, mapping.getDisplayName()),
                LdapAttributes.string(attributes, mapping.getGivenName()),
                LdapAttributes.string(attributes, mapping.getSurname()),
                LdapAttributes.string(attributes, mapping.getEmail()),
                LdapAttributes.string(attributes, mapping.getTelephoneNumber()),
                LdapAttributes.string(attributes, mapping.getTitle()),
                LdapAttributes.string(attributes, mapping.getEmployeeType()),
                LdapAttributes.string(attributes, mapping.getCountry()),
                LdapAttributes.string(attributes, mapping.getOrganization()),
                LdapAttributes.string(attributes, mapping.getOrganizationalUnit()),
                LdapAttributes.binaries(attributes, mapping.getCertificate()));
    }

    private LdapServerEntry toServer(DirContextOperations ctx) throws NamingException {
        LdapProperties.Server mapping = properties.getServer();
        Attributes attributes = ctx.getAttributes();
        return new LdapServerEntry(
                ctx.getNameInNamespace(),
                LdapAttributes.string(attributes, mapping.getCommonName()),
                LdapAttributes.string(attributes, mapping.getFqdn()),
                LdapAttributes.string(attributes, mapping.getDescription()),
                LdapAttributes.string(attributes, mapping.getSerialNumber()),
                LdapAttributes.string(attributes, mapping.getOperatingSystem()),
                LdapAttributes.strings(attributes, mapping.getServerPoc()),
                LdapAttributes.string(attributes, mapping.getOrganization()),
                LdapAttributes.string(attributes, mapping.getOrganizationalUnit()),
                LdapAttributes.binaries(attributes, mapping.getCertificate()));
    }

    private <T> List<T> search(String base, String filter, String[] attributes, ThrowingMapper<T> mapper) {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(attributes);
        controls.setCountLimit(properties.getCountLimit());
        controls.setTimeLimit((int) properties.getSearchTimeout().toMillis());

        ContextMapper<T> contextMapper = ctx -> {
            try {
                return mapper.map((DirContextOperations) ctx);
            } catch (NamingException e) {
                throw new DirectoryReadException("Failed to read entry from the directory", e);
            }
        };

        List<T> results = properties.isPaged()
                ? searchPaged(base, filter, controls, contextMapper)
                : new LdapTemplate(contextSource).search(base, filter, controls, contextMapper);
        log.debug("Directory search base='{}' filter='{}' returned {} entries", base, filter, results.size());
        return results;
    }

    /**
     * Runs the search across pages on a single connection. The paged results control is
     * stateful on the server side, so every page has to travel down the same connection -
     * which is what {@code SingleContextSource} guarantees.
     */
    private <T> List<T> searchPaged(String base, String filter, SearchControls controls, ContextMapper<T> mapper) {
        return org.springframework.ldap.core.support.SingleContextSource.doWithSingleContext(
                contextSource, operations -> {
                    List<T> all = new ArrayList<>();
                    PagedResultsDirContextProcessor processor =
                            new PagedResultsDirContextProcessor(properties.getPageSize());
                    do {
                        all.addAll(operations.search(base, filter, controls, mapper, processor));
                    } while (processor.hasMore());
                    return all;
                });
    }

    @FunctionalInterface
    private interface ThrowingMapper<T> {
        T map(DirContextOperations ctx) throws NamingException;
    }
}

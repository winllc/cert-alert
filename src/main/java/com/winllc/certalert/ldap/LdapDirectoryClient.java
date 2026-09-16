package com.winllc.certalert.ldap;

import java.util.List;
import java.util.function.Consumer;
import javax.naming.NamingException;
import javax.naming.directory.SearchControls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.control.PagedResultsDirContextProcessor;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.ContextSource;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.SingleContextSource;
import org.springframework.stereotype.Component;

/**
 * Reads IC Persons and IC Non-Person Entities out of the directory.
 *
 * <p>Results are handed to a consumer a page at a time and never collected into a list.
 * On a directory of 100,000+ entries, each carrying certificates of a kilobyte or two,
 * materialising the whole result set would cost hundreds of megabytes before a single row
 * reached the database; this way only one page is ever live.
 *
 * <p>Searches are paged for the same reason they are streamed: every directory enforces an
 * administrative size limit, and an unpaged search silently stops there, which here would
 * mean quietly forgetting certificates.
 */
@Component
public class LdapDirectoryClient {

    private static final Logger log = LoggerFactory.getLogger(LdapDirectoryClient.class);

    private final ContextSource contextSource;
    private final LdapProperties properties;
    private final DirectoryEntryMapper mapper;

    public LdapDirectoryClient(
            ContextSource contextSource, LdapProperties properties, DirectoryEntryMapper mapper) {
        this.contextSource = contextSource;
        this.properties = properties;
        this.mapper = mapper;
    }

    /**
     * Streams every IC Person to {@code consumer}.
     *
     * @return how many entries were read
     */
    public int forEachUser(Consumer<LdapUserEntry> consumer) {
        LdapProperties.User mapping = properties.getUser();
        return search(
                mapping.getSearchBase(),
                mapping.getSearchFilter(),
                List.of(mapper.userAttributes()),
                consumer,
                mapper::toUser);
    }

    /**
     * Streams every IC Non-Person Entity to {@code consumer}.
     *
     * @return how many entries were read
     */
    public int forEachServer(Consumer<LdapServerEntry> consumer) {
        LdapProperties.Server mapping = properties.getServer();
        return search(
                mapping.getSearchBase(),
                mapping.getSearchFilter(),
                List.of(mapper.serverAttributes()),
                consumer,
                mapper::toServer);
    }

    private <T> int search(
            String base, String filter, List<String> attributes, Consumer<T> consumer, ThrowingMapper<T> mapper) {

        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setReturningAttributes(attributes.toArray(String[]::new));
        controls.setCountLimit(properties.getCountLimit());
        controls.setTimeLimit((int) properties.getSearchTimeout().toMillis());

        // The mapper runs per entry; handing each result straight to the consumer is what
        // keeps a single page, rather than the whole directory, in memory.
        ContextMapper<Void> contextMapper = ctx -> {
            try {
                consumer.accept(mapper.map((DirContextOperations) ctx));
            } catch (NamingException e) {
                throw new DirectoryReadException("Failed to read entry from the directory", e);
            }
            return null;
        };

        int count = properties.isPaged()
                ? searchPaged(base, filter, controls, contextMapper)
                : new LdapTemplate(contextSource).search(base, filter, controls, contextMapper).size();
        log.debug("Directory search base='{}' filter='{}' returned {} entries", base, filter, count);
        return count;
    }

    /**
     * Runs the search across pages on a single connection. The paged results control is
     * stateful on the server side, so every page has to travel down the same connection -
     * which is what {@code SingleContextSource} guarantees.
     */
    private int searchPaged(String base, String filter, SearchControls controls, ContextMapper<Void> mapper) {
        return SingleContextSource.doWithSingleContext(contextSource, operations -> {
            PagedResultsDirContextProcessor processor = new PagedResultsDirContextProcessor(properties.getPageSize());
            int total = 0;
            int page = 0;
            do {
                // The returned list holds one null per entry; the consumer already has the
                // data, so nothing accumulates here beyond the page's own row count.
                total += operations.search(base, filter, controls, mapper, processor).size();
                if (++page % 20 == 0) {
                    log.info("Directory search in progress: {} entries read from '{}'", total, base);
                }
            } while (processor.hasMore());
            return total;
        });
    }

    /** Maps one entry, allowed to fail the way JNDI does. */
    @FunctionalInterface
    private interface ThrowingMapper<T> {
        T map(DirContextOperations ctx) throws NamingException;
    }
}

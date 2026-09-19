package com.winllc.certalert.ldap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.DirContext;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.SearchControls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ldap.autoconfigure.LdapConnectionDetails;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.stereotype.Component;

/**
 * Reads and writes attributes on one directory entry, by its distinguished name.
 *
 * <p>The managed attributes are the directory's own, so this is where they live: read when
 * a page asks for them and written straight back when somebody changes one. Nothing is
 * cached in between, because a copy of an attribute that can be edited in two places is a
 * copy that will disagree with itself.
 *
 * <p>Rooted at nothing, like the changelog connection and for the same reason: a cached
 * entry carries the distinguished name the directory gave it, which is absolute, and the
 * shared context source would resolve it again under {@code spring.ldap.base}. Built here
 * rather than published as a bean, or Spring Boot would take it for the application's own
 * context source and the sweep would start looking for {@code ou=people} at the root.
 */
@Component
public class LdapServerAttributes {

    private static final Logger log = LoggerFactory.getLogger(LdapServerAttributes.class);

    private final LdapTemplate template;
    private final DirectoryEntryMapper mapper;
    private final LdapProperties properties;
    private final boolean credentialled;

    public LdapServerAttributes(
            LdapConnectionDetails connectionDetails, DirectoryEntryMapper mapper, LdapProperties properties) {

        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrls(connectionDetails.getUrls());
        // Empty on purpose: see the class javadoc.
        contextSource.setBase("");
        this.credentialled = connectionDetails.getUsername() != null
                && !connectionDetails.getUsername().isBlank();
        if (credentialled) {
            contextSource.setUserDn(connectionDetails.getUsername());
            contextSource.setPassword(connectionDetails.getPassword());
        }
        contextSource.afterPropertiesSet();

        this.template = new LdapTemplate(contextSource);
        this.mapper = mapper;
        this.properties = properties;
    }

    /**
     * What the entry holds for each of these attributes, in the directory's own order.
     *
     * @return a value list per attribute asked for; an attribute the entry does not carry
     *     is absent rather than empty, which is the same thing to every caller here
     */
    public Map<String, List<String>> read(String dn, Collection<String> attributes) {
        if (attributes.isEmpty()) {
            return Map.of();
        }
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.OBJECT_SCOPE);
        controls.setReturningAttributes(attributes.toArray(String[]::new));
        controls.setCountLimit(1);

        List<Map<String, List<String>>> found = template.search(
                dn, "(objectClass=*)", controls, (ContextMapper<Map<String, List<String>>>) ctx -> {
                    try {
                        Attributes source = ((DirContextOperations) ctx).getAttributes();
                        Map<String, List<String>> held = new LinkedHashMap<>();
                        for (String attribute : attributes) {
                            List<String> values = List.copyOf(LdapAttributes.strings(source, attribute));
                            if (!values.isEmpty()) {
                                held.put(attribute, values);
                            }
                        }
                        return held;
                    } catch (NamingException e) {
                        throw new DirectoryReadException("Failed to read " + dn, e);
                    }
                });
        return found.isEmpty() ? Map.of() : found.getFirst();
    }

    /**
     * Makes the entry hold exactly these values for this attribute.
     *
     * <p>One replace rather than adds and removes: the page edits an attribute as a whole,
     * and a replace is atomic where a sequence is not. An empty list takes the attribute off
     * the entry, which is what "no value" means in a directory - there is no such thing as
     * an attribute present and empty.
     */
    public void replace(String dn, String attribute, List<String> values) {
        BasicAttribute replacement = new BasicAttribute(attribute);
        values.forEach(replacement::add);
        template.modifyAttributes(dn, new ModificationItem[] {
            new ModificationItem(DirContext.REPLACE_ATTRIBUTE, replacement)
        });
        log.info("Set {} on {} to {}", attribute, dn, values.isEmpty() ? "nothing" : values);
    }

    /** The entry as the sweep would read it, so a cached copy can be brought back in step. */
    public LdapServerEntry reread(String dn) {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.OBJECT_SCOPE);
        controls.setReturningAttributes(mapper.serverAttributes());
        controls.setCountLimit(1);

        List<LdapServerEntry> found = template.search(
                dn, properties.getServer().getSearchFilter(), controls, (ContextMapper<LdapServerEntry>) ctx -> {
                    try {
                        return mapper.toServer((DirContextOperations) ctx);
                    } catch (NamingException e) {
                        throw new DirectoryReadException("Failed to read " + dn, e);
                    }
                });
        return found.isEmpty() ? null : found.getFirst();
    }

    /**
     * Whether this application binds as anybody. An anonymous connection can read a public
     * directory and will not be writing to it, so the pages say so rather than offering an
     * edit that the directory will refuse.
     */
    public boolean canWrite() {
        return credentialled;
    }

    /** Attribute names, deduplicated and in a stable order. */
    public static List<String> namesOf(Collection<String> attributes) {
        List<String> names = new ArrayList<>();
        attributes.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .forEach(name -> {
                    if (names.stream().noneMatch(seen -> seen.equalsIgnoreCase(name))) {
                        names.add(name);
                    }
                });
        return names;
    }
}

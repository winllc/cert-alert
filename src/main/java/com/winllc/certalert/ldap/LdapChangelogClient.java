package com.winllc.certalert.ldap;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the directory's changelog.
 *
 * <p>Runs on its own base-less connection, because the changelog suffix and the root DSE
 * both sit outside the data tree the rest of the application works in.
 */
@Component
@ConditionalOnProperty(prefix = "cert-alert.ldap.changelog", name = "enabled", havingValue = "true")
public class LdapChangelogClient {

    private static final Logger log = LoggerFactory.getLogger(LdapChangelogClient.class);

    private final LdapTemplate ldapTemplate;
    private final ChangelogProperties properties;

    public LdapChangelogClient(ChangelogConnection connection, ChangelogProperties properties) {
        this.ldapTemplate = connection.template();
        this.properties = properties;
    }

    /**
     * The range of change numbers the directory still holds, read from the root DSE.
     *
     * <p>Returns {@link ChangelogBounds#UNKNOWN} rather than failing when the directory
     * publishes nothing: the bounds inform gap detection and lag reporting, and neither is
     * worth stopping the connector over.
     */
    public ChangelogBounds readBounds() {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.OBJECT_SCOPE);
        controls.setReturningAttributes(new String[] {
            properties.getFirstChangeNumberAttribute(), properties.getLastChangeNumberAttribute()
        });

        try {
            List<ChangelogBounds> found = ldapTemplate.search("", "(objectClass=*)", controls,
                    (ContextMapper<ChangelogBounds>) ctx -> {
                        Attributes attributes = ((DirContextOperations) ctx).getAttributes();
                        return new ChangelogBounds(
                                readLong(attributes, properties.getFirstChangeNumberAttribute()),
                                readLong(attributes, properties.getLastChangeNumberAttribute()));
                    });
            return found.isEmpty() ? ChangelogBounds.UNKNOWN : found.getFirst();
        } catch (RuntimeException e) {
            log.debug("Directory published no change number bounds on the root DSE", e);
            return ChangelogBounds.UNKNOWN;
        }
    }

    /**
     * The next batch of changes after {@code afterChangeNumber}, in order.
     *
     * <p>Every entry's number is re-checked here rather than trusted from the filter. The
     * filter's matching rule depends on the directory having {@code changeNumber} indexed
     * with an integer ordering rule, and where it does not the comparison quietly becomes a
     * string one - which would make the connector repeat or skip work. Sorting is for the
     * same reason: the cursor may only ever move forward.
     */
    public List<ChangelogEntry> readChanges(long afterChangeNumber, int batchSize) {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.ONELEVEL_SCOPE);
        controls.setCountLimit(batchSize);
        controls.setReturningAttributes(new String[] {
            properties.getChangeNumberAttribute(),
            properties.getTargetDnAttribute(),
            properties.getChangeTypeAttribute(),
            properties.getNewRdnAttribute(),
            properties.getNewSuperiorAttribute()
        });

        String filter = MessageFormat.format(properties.getFilter(), String.valueOf(afterChangeNumber + 1));
        List<ChangelogEntry> entries = ldapTemplate.search(
                properties.getBaseDn(), filter, controls, (ContextMapper<ChangelogEntry>) ctx -> {
                    try {
                        return toEntry(((DirContextOperations) ctx).getAttributes());
                    } catch (NamingException e) {
                        throw new DirectoryReadException("Failed to read a changelog entry", e);
                    }
                });

        List<ChangelogEntry> ordered = new ArrayList<>(entries.size());
        for (ChangelogEntry entry : entries) {
            if (entry != null && entry.changeNumber() > afterChangeNumber) {
                ordered.add(entry);
            }
        }
        ordered.sort(Comparator.comparingLong(ChangelogEntry::changeNumber));
        return ordered;
    }

    private ChangelogEntry toEntry(Attributes attributes) throws NamingException {
        Long changeNumber = readLong(attributes, properties.getChangeNumberAttribute());
        String targetDn = LdapAttributes.string(attributes, properties.getTargetDnAttribute());
        if (changeNumber == null || targetDn == null) {
            // Not a change we can act on; the connector counts and steps over it.
            return null;
        }
        return new ChangelogEntry(
                changeNumber,
                targetDn,
                ChangelogEntry.ChangeType.parse(
                        LdapAttributes.string(attributes, properties.getChangeTypeAttribute())),
                LdapAttributes.string(attributes, properties.getNewRdnAttribute()),
                LdapAttributes.string(attributes, properties.getNewSuperiorAttribute()));
    }

    private Long readLong(Attributes attributes, String name) {
        try {
            String value = LdapAttributes.string(attributes, name);
            return value == null ? null : Long.parseLong(value.trim());
        } catch (NamingException | NumberFormatException e) {
            log.debug("Attribute '{}' was not a change number", name, e);
            return null;
        }
    }
}

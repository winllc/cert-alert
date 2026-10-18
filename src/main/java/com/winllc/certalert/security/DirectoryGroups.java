package com.winllc.certalert.security;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.naming.InvalidNameException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.ldap.LdapName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.ldap.support.LdapUtils;
import org.springframework.stereotype.Component;

/**
 * The directory groups a person is in: the {@code memberOf} values of their entry, read with
 * the service account when they sign in.
 *
 * <p>Read only where a group grants something ({@code cert-alert.security.groups.admin} or
 * {@code auditor}), and remembered for {@code cache-for}, because a client certificate is
 * checked on every request. A directory that cannot be read gives no groups - and so no role
 * from one - rather than refusing the sign-in, which the directory-password and certificate
 * paths would otherwise lose to an outage of a different kind.
 */
@Component
public class DirectoryGroups {

    private static final Logger log = LoggerFactory.getLogger(DirectoryGroups.class);

    private static final int MAX_REMEMBERED = 10_000;

    private final BaseLdapPathContextSource contextSource;
    private final SecurityProperties properties;
    private final Clock clock;

    private record Remembered(Set<String> groups, Instant until) {}

    private final Map<String, Remembered> remembered = new ConcurrentHashMap<>();

    public DirectoryGroups(BaseLdapPathContextSource contextSource, SecurityProperties properties, Clock clock) {
        this.contextSource = contextSource;
        this.properties = properties;
        this.clock = clock;
    }

    /** The groups of the entry at this DN, normalised; empty where it has none or is not readable. */
    public Set<String> of(String dn) {
        RoleProperties groups = properties.getGroups();
        if (dn == null || dn.isBlank() || !groups.isDirectory() || !groups.anyMapped()) {
            return Set.of();
        }
        String key = DirectoryPrincipalResolver.normalise(dn);
        Instant now = Instant.now(clock);
        Remembered held = remembered.get(key);
        if (held != null && now.isBefore(held.until())) {
            return held.groups();
        }
        Set<String> read = read(dn, groups.getMemberOfAttribute());
        if (remembered.size() > MAX_REMEMBERED) {
            remembered.clear();
        }
        remembered.put(key, new Remembered(read, now.plus(groups.getCacheFor())));
        return read;
    }

    /** Forgets what was read, so the next sign-in reads again. */
    public void forget() {
        remembered.clear();
    }

    private Set<String> read(String dn, String attribute) {
        LdapName name;
        try {
            name = new LdapName(dn);
        } catch (InvalidNameException e) {
            return Set.of();
        }
        LdapName base = contextSource.getBaseLdapName();
        if (!base.isEmpty() && !name.startsWith(base)) {
            return Set.of();
        }
        DirContext context = null;
        try {
            context = contextSource.getReadOnlyContext();
            Attributes attributes = context.getAttributes(LdapUtils.removeFirst(name, base), new String[] {attribute});
            return values(attributes.get(attribute));
        } catch (NamingException | RuntimeException e) {
            log.warn("Could not read the groups of {}; no role is granted by a group this time: {}", dn, e.toString());
            return Set.of();
        } finally {
            if (context != null) {
                try {
                    context.close();
                } catch (NamingException ignored) {
                    // nothing more to do with it
                }
            }
        }
    }

    private static Set<String> values(Attribute attribute) throws NamingException {
        Set<String> values = new LinkedHashSet<>();
        if (attribute == null) {
            return values;
        }
        NamingEnumeration<?> all = attribute.getAll();
        while (all.hasMore()) {
            Object value = all.next();
            if (value != null) {
                values.add(DirectoryPrincipalResolver.normalise(value.toString()));
            }
        }
        values.remove("");
        return Set.copyOf(values);
    }

    /** Normalised, for comparing with what the configuration lists. */
    static Set<String> normaliseAll(Collection<String> groups) {
        Set<String> values = new LinkedHashSet<>();
        if (groups != null) {
            groups.stream().map(DirectoryPrincipalResolver::normalise).filter(v -> !v.isEmpty()).forEach(values::add);
        }
        return values;
    }
}

package com.winllc.certalert.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.naming.InvalidNameException;
import javax.naming.NameNotFoundException;
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
 * Where a person stands in the directory <em>now</em>: whether their entry still exists,
 * whether the account is disabled or locked, and the roles the directory alone gives them.
 *
 * <p>For what acts on somebody's authority long after they signed in - an API token, a
 * scheduled report - which must stop when the person stops: deleted, disabled, locked, or no
 * longer an administrator. Read from the live directory with the service account, never from
 * the cached copy (which keeps entries the sweep has stopped seeing until a prune) and never
 * from a sign-in's roles: a single sign-on provider's groups grant nothing here, so a token
 * can never outlive its creator's removal from a provider group.
 *
 * <p>Only an {@link State#ACTIVE} answer is remembered, and only for {@code cacheFor}; a
 * refusal, and a directory that cannot be read, are read again every time - and a directory
 * that cannot be read refuses.
 *
 * <p>Disabled or locked: {@code nsAccountLock: true} (389 Directory Server, Red Hat IdM),
 * {@code userAccountControl} with ACCOUNTDISABLE (Active Directory), {@code lockoutTime}
 * other than 0 (Active Directory), and any {@code pwdAccountLockedTime} (OpenLDAP and 389's
 * password policy).
 */
@Component
public class DirectoryStanding {

    private static final Logger log = LoggerFactory.getLogger(DirectoryStanding.class);

    private static final int MAX_REMEMBERED = 10_000;

    /** Active Directory's ACCOUNTDISABLE bit in userAccountControl. */
    private static final int ACCOUNTDISABLE = 0x2;

    public enum State {
        /** The entry exists and its account is usable. */
        ACTIVE,
        /** No such entry, or one outside the directory this application reads. */
        GONE,
        /** The account is disabled or locked. */
        DISABLED,
        /** The directory could not be read. */
        UNREACHABLE
    }

    /**
     * @param roles {@code ROLE_*} authorities the directory and the configuration grant;
     *     empty unless active
     */
    public record Standing(State state, Set<String> roles, String reason) {

        public boolean active() {
            return state == State.ACTIVE;
        }
    }

    private record Remembered(Standing standing, Instant until) {}

    private final BaseLdapPathContextSource contextSource;
    private final SecurityProperties properties;
    private final DirectoryPrincipalResolver resolver;
    private final Clock clock;
    private final Map<String, Remembered> remembered = new ConcurrentHashMap<>();

    public DirectoryStanding(
            BaseLdapPathContextSource contextSource,
            SecurityProperties properties,
            DirectoryPrincipalResolver resolver,
            Clock clock) {
        this.contextSource = contextSource;
        this.properties = properties;
        this.resolver = resolver;
        this.clock = clock;
    }

    /**
     * @param cacheFor how long an active answer may be reused; zero reads every time
     */
    public Standing of(String dn, Duration cacheFor) {
        if (dn == null || dn.isBlank()) {
            return new Standing(State.GONE, Set.of(), "no directory entry");
        }
        String key = DirectoryPrincipalResolver.normalise(dn);
        Instant now = Instant.now(clock);
        Remembered held = remembered.get(key);
        if (held != null && now.isBefore(held.until())) {
            return held.standing();
        }
        remembered.remove(key);
        Standing standing = read(dn);
        if (standing.active() && cacheFor != null && !cacheFor.isZero() && !cacheFor.isNegative()) {
            if (remembered.size() > MAX_REMEMBERED) {
                remembered.clear();
            }
            remembered.put(key, new Remembered(standing, now.plus(cacheFor)));
        }
        return standing;
    }

    /** Forgets every remembered answer. */
    public void forget() {
        remembered.clear();
    }

    private Standing read(String dn) {
        LdapName name;
        try {
            name = new LdapName(dn);
        } catch (InvalidNameException e) {
            return new Standing(State.GONE, Set.of(), "not a distinguished name");
        }
        LdapName base = contextSource.getBaseLdapName();
        if (!base.isEmpty() && !name.startsWith(base)) {
            return new Standing(State.GONE, Set.of(), "outside the directory this application reads");
        }
        String memberOf = properties.getGroups().getMemberOfAttribute();
        DirContext context = null;
        try {
            context = contextSource.getReadOnlyContext();
            Attributes attributes = context.getAttributes(LdapUtils.removeFirst(name, base), new String[] {
                "uid", memberOf, "nsAccountLock", "userAccountControl", "lockoutTime", "pwdAccountLockedTime"
            });
            String disabled = disabledBy(attributes);
            if (disabled != null) {
                return new Standing(State.DISABLED, Set.of(), disabled);
            }
            Set<String> groups = properties.getGroups().isDirectory() ? values(attributes.get(memberOf)) : Set.of();
            Set<String> roles = resolver.directoryRoles(dn, values(attributes.get("uid")), groups);
            return new Standing(State.ACTIVE, roles, null);
        } catch (NameNotFoundException e) {
            return new Standing(State.GONE, Set.of(), "the entry is no longer in the directory");
        } catch (NamingException | org.springframework.ldap.NamingException e) {
            if (e instanceof org.springframework.ldap.NameNotFoundException) {
                return new Standing(State.GONE, Set.of(), "the entry is no longer in the directory");
            }
            log.warn("Could not read the directory entry {}; refusing what acts on its authority: {}", dn, e.toString());
            return new Standing(State.UNREACHABLE, Set.of(), "the directory could not be read");
        } catch (RuntimeException e) {
            log.warn("Could not read the directory entry {}; refusing what acts on its authority: {}", dn, e.toString());
            return new Standing(State.UNREACHABLE, Set.of(), "the directory could not be read");
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

    /** Why this account may not be used, or null where nothing says so. */
    static String disabledBy(Attributes attributes) throws NamingException {
        String lock = first(attributes.get("nsAccountLock"));
        if (lock != null && lock.trim().equalsIgnoreCase("true")) {
            return "the account is locked (nsAccountLock)";
        }
        String control = first(attributes.get("userAccountControl"));
        if (control != null) {
            try {
                if ((Integer.parseInt(control.trim()) & ACCOUNTDISABLE) != 0) {
                    return "the account is disabled (userAccountControl)";
                }
            } catch (NumberFormatException e) {
                return "the account's userAccountControl cannot be read";
            }
        }
        String lockout = first(attributes.get("lockoutTime"));
        if (lockout != null && !lockout.trim().isEmpty() && !lockout.trim().equals("0")) {
            return "the account is locked out (lockoutTime)";
        }
        if (first(attributes.get("pwdAccountLockedTime")) != null) {
            return "the account is locked (pwdAccountLockedTime)";
        }
        return null;
    }

    private static String first(Attribute attribute) throws NamingException {
        if (attribute == null || attribute.size() == 0) {
            return null;
        }
        Object value = attribute.get();
        return value == null ? null : value.toString();
    }

    private static Set<String> values(Attribute attribute) throws NamingException {
        Set<String> values = new LinkedHashSet<>();
        if (attribute == null) {
            return values;
        }
        NamingEnumeration<?> all = attribute.getAll();
        while (all.hasMore()) {
            Object value = all.next();
            if (value != null && !value.toString().isBlank()) {
                values.add(DirectoryPrincipalResolver.normalise(value.toString()));
            }
        }
        return values;
    }
}

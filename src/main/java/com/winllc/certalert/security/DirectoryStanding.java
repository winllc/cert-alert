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
 * <p>Disabled or locked - and only while it still is:
 *
 * <ul>
 *   <li>{@code nsAccountLock: true} (389 Directory Server, Red Hat IdM);
 *   <li>Active Directory's {@code userAccountControl} with ACCOUNTDISABLE;
 *   <li>Active Directory's lockout: the constructed {@code msDS-User-Account-Control-Computed}
 *       with UF_LOCKOUT, which AD clears itself when the lockout lapses. Where a directory
 *       does not compute it, {@code lockoutTime} counts only while it is within the domain's
 *       {@code lockoutDuration}, read from the base entry; an account whose duration cannot be
 *       read is not taken as locked by {@code lockoutTime} alone, which outlives the lockout;
 *   <li>{@code pwdAccountLockedTime} (OpenLDAP and 389's password policy): {@code 000001010000Z}
 *       is locked until an administrator unlocks it; any other time only while it is within
 *       the {@code pwdLockoutDuration} of the entry's policy ({@code pwdPolicySubentry}), where
 *       that can be read.
 * </ul>
 */
@Component
public class DirectoryStanding {

    private static final Logger log = LoggerFactory.getLogger(DirectoryStanding.class);

    private static final int MAX_REMEMBERED = 10_000;

    /** Active Directory's ACCOUNTDISABLE bit in userAccountControl. */
    private static final int ACCOUNTDISABLE = 0x2;

    /** Active Directory's UF_LOCKOUT bit in msDS-User-Account-Control-Computed. */
    private static final int UF_LOCKOUT = 0x10;

    /** OpenLDAP's "locked until an administrator unlocks it". */
    static final String PERMANENT_LOCK = "000001010000Z";

    /** Seconds from 1601-01-01 (Windows FILETIME's epoch) to 1970-01-01. */
    private static final long FILETIME_EPOCH_OFFSET = 11_644_473_600L;

    /**
     * Reads what a lockout's length is decided by, where it can be read: null otherwise.
     */
    interface Policies {
        /** Active Directory's domain lockoutDuration: negative 100-nanosecond intervals. */
        String domainLockoutDuration();

        /** A password policy entry's pwdLockoutDuration, in seconds. */
        String pwdLockoutDuration(String policyDn);
    }

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
    private final com.winllc.certalert.ldap.LdapProperties ldap;
    private final Clock clock;
    private final Map<String, Remembered> remembered = new ConcurrentHashMap<>();

    public DirectoryStanding(
            BaseLdapPathContextSource contextSource,
            SecurityProperties properties,
            DirectoryPrincipalResolver resolver,
            com.winllc.certalert.ldap.LdapProperties ldap,
            Clock clock) {
        this.contextSource = contextSource;
        this.properties = properties;
        this.resolver = resolver;
        this.ldap = ldap;
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
        // The attribute this deployment names people's uid by (cert-alert.ldap.user.uid), as
        // admin-identifiers are matched against it everywhere else.
        String uid = com.winllc.certalert.ldap.UserField.UID.attributeName(ldap.getUser());
        DirContext context = null;
        try {
            context = contextSource.getReadOnlyContext();
            Attributes attributes = context.getAttributes(LdapUtils.removeFirst(name, base), new String[] {
                uid, memberOf, "nsAccountLock", "userAccountControl", "msDS-User-Account-Control-Computed",
                "lockoutTime", "pwdAccountLockedTime", "pwdPolicySubentry"
            });
            DirContext reading = context;
            String disabled = disabledBy(attributes, Instant.now(clock), new Policies() {
                @Override
                public String domainLockoutDuration() {
                    return readQuietly(reading, base, null, "lockoutDuration");
                }

                @Override
                public String pwdLockoutDuration(String policyDn) {
                    return readQuietly(reading, base, policyDn, "pwdLockoutDuration");
                }
            });
            if (disabled != null) {
                return new Standing(State.DISABLED, Set.of(), disabled);
            }
            Set<String> groups = properties.getGroups().isDirectory() ? values(attributes.get(memberOf)) : Set.of();
            Set<String> roles = resolver.directoryRoles(dn, values(attributes.get(uid)), groups);
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

    /** Why this account may not be used now, or null where nothing says so. */
    static String disabledBy(Attributes attributes, Instant now, Policies policies) throws NamingException {
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
        String computed = first(attributes.get("msDS-User-Account-Control-Computed"));
        if (computed != null) {
            try {
                if ((Integer.parseInt(computed.trim()) & UF_LOCKOUT) != 0) {
                    return "the account is locked out (msDS-User-Account-Control-Computed)";
                }
            } catch (NumberFormatException e) {
                return "the account's msDS-User-Account-Control-Computed cannot be read";
            }
        } else if (adLockedOut(first(attributes.get("lockoutTime")), now, policies)) {
            return "the account is locked out (lockoutTime, within the domain's lockoutDuration)";
        }
        String locked = first(attributes.get("pwdAccountLockedTime"));
        if (locked != null && pwdLocked(locked.trim(), first(attributes.get("pwdPolicySubentry")), now, policies)) {
            return "the account is locked (pwdAccountLockedTime)";
        }
        return null;
    }

    /** lockoutTime (a FILETIME) still within the domain's lockoutDuration; false where that cannot be read. */
    static boolean adLockedOut(String lockoutTime, Instant now, Policies policies) {
        if (lockoutTime == null || lockoutTime.isBlank() || lockoutTime.trim().equals("0")) {
            return false;
        }
        long filetime;
        try {
            filetime = Long.parseLong(lockoutTime.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        String durationText = policies.domainLockoutDuration();
        if (durationText == null) {
            return false;
        }
        long duration;
        try {
            duration = Long.parseLong(durationText.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        // 0 and the most negative value both mean "until an administrator unlocks it".
        if (duration == 0 || duration == Long.MIN_VALUE) {
            return true;
        }
        Instant lockedAt = Instant.ofEpochSecond(filetime / 10_000_000L - FILETIME_EPOCH_OFFSET, (filetime % 10_000_000L) * 100);
        Instant until = lockedAt.plusNanos(Math.abs(duration) * 100);
        return now.isBefore(until);
    }

    /** pwdAccountLockedTime: permanent, or within the policy's pwdLockoutDuration where that can be read. */
    static boolean pwdLocked(String lockedTime, String policyDn, Instant now, Policies policies) {
        if (PERMANENT_LOCK.equals(lockedTime)) {
            return true;
        }
        Instant lockedAt = generalizedTime(lockedTime);
        if (lockedAt == null || policyDn == null) {
            return false;
        }
        String durationText = policies.pwdLockoutDuration(policyDn);
        if (durationText == null) {
            return false;
        }
        long seconds;
        try {
            seconds = Long.parseLong(durationText.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        // 0: locked until an administrator unlocks it.
        return seconds == 0 || now.isBefore(lockedAt.plusSeconds(seconds));
    }

    /** 20261021120000Z, with or without fractions of a second; null for anything else. */
    static Instant generalizedTime(String value) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{4})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(?:[.,]\\d+)?Z").matcher(value);
        if (!m.matches()) {
            return null;
        }
        try {
            return java.time.LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                    Integer.parseInt(m.group(6))).toInstant(java.time.ZoneOffset.UTC);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    /** One value of an entry under the base (the base itself for null), or null where it cannot be read. */
    private static String readQuietly(DirContext context, LdapName base, String dn, String attribute) {
        try {
            javax.naming.Name relative;
            if (dn == null) {
                relative = new LdapName("");
            } else {
                LdapName name = new LdapName(dn);
                if (!base.isEmpty() && !name.startsWith(base)) {
                    return null;
                }
                relative = LdapUtils.removeFirst(name, base);
            }
            return first(context.getAttributes(relative, new String[] {attribute}).get(attribute));
        } catch (NamingException | RuntimeException e) {
            return null;
        }
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

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
 * <p>Disabled or locked. A lock fails closed: it is taken as lapsed only where a known,
 * non-zero lockout length shows that it has, and anything that cannot be read or parsed
 * along the way refuses.
 *
 * <ul>
 *   <li>{@code nsAccountLock: true} (389 Directory Server, Red Hat IdM);
 *   <li>Active Directory's {@code userAccountControl} with ACCOUNTDISABLE;
 *   <li>Active Directory's lockout: the constructed {@code msDS-User-Account-Control-Computed}
 *       with UF_LOCKOUT, which AD clears itself when the lockout lapses. Where a directory
 *       does not compute it, a non-zero {@code lockoutTime} is locked unless the domain's
 *       {@code lockoutDuration} (read from the base entry) is known, non-zero and has passed;
 *       0 and the "never" value are locked until an administrator unlocks it;
 *   <li>{@code pwdAccountLockedTime} (OpenLDAP and 389's password policy): {@code 000001010000Z}
 *       is locked until an administrator unlocks it; any other time is locked unless the
 *       {@code pwdLockoutDuration} of the entry's policy ({@code pwdPolicySubentry}) is known,
 *       non-zero and has passed. A policy entry without one means 0: locked;
 *   <li>{@code accountUnlockTime} (389's account policy): locked while it is in the future, and
 *       {@code 19700101000000Z} is locked until an administrator unlocks it.
 * </ul>
 *
 * <p>Where the lockout length cannot be found at all - no {@code pwdPolicySubentry} (a policy
 * applied by {@code olcPPolicyDefault}), a policy entry that is not there, or a domain with no
 * {@code lockoutDuration} - {@code cert-alert.ldap.lockout.assumed-duration} is assumed if it
 * is set; unset, the account is locked. A directory that refuses or fails one of these reads
 * is unreachable, which refuses too.
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

    /** 389's accountUnlockTime for "locked until an administrator unlocks it". */
    static final String ADMIN_UNLOCK = "19700101000000Z";

    /** Seconds from 1601-01-01 (Windows FILETIME's epoch) to 1970-01-01. */
    private static final long FILETIME_EPOCH_OFFSET = 11_644_473_600L;

    /**
     * A value read from an entry that decides a lockout's length.
     *
     * @param entryFound false where there is no such entry (or none this application can read
     *     under its base): the length is unknown
     * @param value the attribute's value, or null where the entry has none
     */
    record PolicyValue(boolean entryFound, String value) {

        static final PolicyValue NO_ENTRY = new PolicyValue(false, null);
    }

    /**
     * Reads what a lockout's length is decided by. A directory that fails or refuses the read
     * throws, and the account is then refused as unreachable.
     */
    interface Policies {
        /** Active Directory's domain lockoutDuration (negative 100-nanosecond intervals), from the base entry. */
        PolicyValue domainLockoutDuration() throws NamingException;

        /** A password policy entry's pwdLockoutDuration, in seconds. */
        PolicyValue pwdLockoutDuration(String policyDn) throws NamingException;

        /** The length to assume where none can be found ({@code cert-alert.ldap.lockout.assumed-duration}); null: locked. */
        Duration assumedDuration();
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
                "lockoutTime", "pwdAccountLockedTime", "pwdPolicySubentry", "accountUnlockTime"
            });
            DirContext reading = context;
            String disabled = disabledBy(attributes, Instant.now(clock), new Policies() {
                @Override
                public PolicyValue domainLockoutDuration() throws NamingException {
                    return readPolicy(reading, base, null, "lockoutDuration");
                }

                @Override
                public PolicyValue pwdLockoutDuration(String policyDn) throws NamingException {
                    return readPolicy(reading, base, policyDn, "pwdLockoutDuration");
                }

                @Override
                public Duration assumedDuration() {
                    return ldap.getLockout().getAssumedDuration();
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
            return "the account is locked out (lockoutTime, not known to have lapsed)";
        }
        String locked = first(attributes.get("pwdAccountLockedTime"));
        if (locked != null && pwdLocked(locked.trim(), first(attributes.get("pwdPolicySubentry")), now, policies)) {
            return "the account is locked (pwdAccountLockedTime, not known to have lapsed)";
        }
        String unlockAt = first(attributes.get("accountUnlockTime"));
        if (unlockAt != null && unlockPending(unlockAt.trim(), now)) {
            return "the account is locked (accountUnlockTime)";
        }
        return null;
    }

    /**
     * A non-zero lockoutTime (a FILETIME) is locked unless the domain's lockoutDuration is
     * known, not 0 or "never", and has passed since.
     */
    static boolean adLockedOut(String lockoutTime, Instant now, Policies policies) throws NamingException {
        if (lockoutTime == null || lockoutTime.isBlank() || lockoutTime.trim().equals("0")) {
            return false;
        }
        Instant lockedAt;
        try {
            long filetime = Long.parseLong(lockoutTime.trim());
            lockedAt = Instant.ofEpochSecond(
                    Math.floorDiv(filetime, 10_000_000L) - FILETIME_EPOCH_OFFSET, Math.floorMod(filetime, 10_000_000L) * 100);
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return true;
        }
        PolicyValue read = policies.domainLockoutDuration();
        if (read.value() == null) {
            return lockedAssuming(lockedAt, now, policies.assumedDuration());
        }
        long duration;
        try {
            duration = Long.parseLong(read.value().trim());
        } catch (NumberFormatException e) {
            return true;
        }
        // 0 and the most negative value ("never") both mean "until an administrator unlocks it".
        if (duration == 0 || duration == Long.MIN_VALUE) {
            return true;
        }
        long intervals = Math.abs(duration);
        return lockedFor(lockedAt, Duration.ofSeconds(intervals / 10_000_000L, (intervals % 10_000_000L) * 100), now);
    }

    /**
     * pwdAccountLockedTime is locked unless it is a time and the policy's pwdLockoutDuration is
     * known, not 0, and has passed since.
     */
    static boolean pwdLocked(String lockedTime, String policyDn, Instant now, Policies policies) throws NamingException {
        if (PERMANENT_LOCK.equals(lockedTime)) {
            return true;
        }
        Instant lockedAt = generalizedTime(lockedTime);
        if (lockedAt == null) {
            return true;
        }
        PolicyValue read = policyDn == null || policyDn.isBlank()
                ? PolicyValue.NO_ENTRY
                : policies.pwdLockoutDuration(policyDn.trim());
        if (!read.entryFound()) {
            return lockedAssuming(lockedAt, now, policies.assumedDuration());
        }
        if (read.value() == null) {
            // A policy with no pwdLockoutDuration has the default, 0: until an administrator unlocks it.
            return true;
        }
        long seconds;
        try {
            seconds = Long.parseLong(read.value().trim());
        } catch (NumberFormatException e) {
            return true;
        }
        return seconds <= 0 || lockedFor(lockedAt, Duration.ofSeconds(seconds), now);
    }

    /** accountUnlockTime: locked while it is in the future, and for good at 19700101000000Z; unreadable is locked. */
    static boolean unlockPending(String unlockTime, Instant now) {
        if (ADMIN_UNLOCK.equals(unlockTime)) {
            return true;
        }
        Instant at = generalizedTime(unlockTime);
        return at == null || now.isBefore(at);
    }

    /** Where the length is unknown: the assumed one if there is one, otherwise locked. */
    private static boolean lockedAssuming(Instant lockedAt, Instant now, Duration assumed) {
        return assumed == null || assumed.isZero() || assumed.isNegative() || lockedFor(lockedAt, assumed, now);
    }

    /** Whether a lock of this length from lockedAt still holds now; one too long to reckon does. */
    private static boolean lockedFor(Instant lockedAt, Duration length, Instant now) {
        try {
            return now.isBefore(lockedAt.plus(length));
        } catch (java.time.DateTimeException | ArithmeticException e) {
            return true;
        }
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

    /**
     * One value of an entry under the base (the base itself for null). An entry that is not
     * there, or not under the base, is {@link PolicyValue#NO_ENTRY}; any other failure - the
     * connection, the service account's rights - is thrown, so the account is refused.
     */
    private static PolicyValue readPolicy(DirContext context, LdapName base, String dn, String attribute)
            throws NamingException {
        javax.naming.Name relative;
        if (dn == null) {
            relative = new LdapName("");
        } else {
            LdapName name;
            try {
                name = new LdapName(dn);
            } catch (InvalidNameException e) {
                return PolicyValue.NO_ENTRY;
            }
            if (!base.isEmpty() && !name.startsWith(base)) {
                return PolicyValue.NO_ENTRY;
            }
            relative = LdapUtils.removeFirst(name, base);
        }
        try {
            return new PolicyValue(true, first(context.getAttributes(relative, new String[] {attribute}).get(attribute)));
        } catch (NameNotFoundException e) {
            return PolicyValue.NO_ENTRY;
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

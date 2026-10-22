package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.winllc.certalert.ldap.LdapProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import javax.naming.Name;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;
import javax.naming.NoPermissionException;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.ldap.LdapName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;

/**
 * What makes an account unusable now - a lock failing closed, lapsing only where a known
 * length shows it has - the uid attribute the deployment configured, and a directory that
 * cannot be read refusing, every time.
 */
class DirectoryStandingTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    /** Neither a domain lockoutDuration nor a password policy entry is there to say how long. */
    private static final DirectoryStanding.Policies UNKNOWN = policies(null, null);

    /** The directory refuses or fails every read of a policy. */
    private static final DirectoryStanding.Policies FAILING = new DirectoryStanding.Policies() {
        @Override
        public DirectoryStanding.PolicyValue domainLockoutDuration() throws NamingException {
            throw new NoPermissionException("insufficient access rights");
        }

        @Override
        public DirectoryStanding.PolicyValue pwdLockoutDuration(String policyDn) throws NamingException {
            throw new javax.naming.CommunicationException("connection reset");
        }

        @Override
        public Duration assumedDuration() {
            return Duration.ofMinutes(30);
        }
    };

    /** An entry that is there, with this value (null: without the attribute). */
    private static DirectoryStanding.PolicyValue value(String value) {
        return new DirectoryStanding.PolicyValue(true, value);
    }

    private static DirectoryStanding.Policies policies(DirectoryStanding.PolicyValue domain, DirectoryStanding.PolicyValue pwd) {
        return policies(domain, pwd, null);
    }

    /** Null for an entry that is not there. */
    private static DirectoryStanding.Policies policies(
            DirectoryStanding.PolicyValue domain, DirectoryStanding.PolicyValue pwd, Duration assumed) {
        return new DirectoryStanding.Policies() {
            @Override
            public DirectoryStanding.PolicyValue domainLockoutDuration() {
                return domain == null ? DirectoryStanding.PolicyValue.NO_ENTRY : domain;
            }

            @Override
            public DirectoryStanding.PolicyValue pwdLockoutDuration(String policyDn) {
                return pwd == null ? DirectoryStanding.PolicyValue.NO_ENTRY : pwd;
            }

            @Override
            public Duration assumedDuration() {
                return assumed;
            }
        };
    }

    private static DirectoryStanding.Policies assuming(Duration assumed) {
        return policies(null, null, assumed);
    }

    private static BasicAttributes with(String... pairs) {
        BasicAttributes attributes = new BasicAttributes(true);
        for (int i = 0; i < pairs.length; i += 2) {
            attributes.put(pairs[i], pairs[i + 1]);
        }
        return attributes;
    }

    /** A FILETIME (100 ns since 1601) for this instant. */
    private static String filetime(Instant at) {
        return String.valueOf((at.getEpochSecond() + 11_644_473_600L) * 10_000_000L);
    }

    private static String disabled(BasicAttributes attributes, DirectoryStanding.Policies policies) throws Exception {
        return DirectoryStanding.disabledBy(attributes, NOW, policies);
    }

    @Test
    void disabledAndAdministrativelyLockedAccountsAreRefused() throws Exception {
        assertThat(disabled(with("nsAccountLock", "TRUE"), UNKNOWN)).contains("nsAccountLock");
        assertThat(disabled(with("nsAccountLock", "false"), UNKNOWN)).isNull();
        assertThat(disabled(with("userAccountControl", "514"), UNKNOWN)).contains("disabled");
        assertThat(disabled(with("userAccountControl", "512"), UNKNOWN)).isNull();
        assertThat(disabled(with("userAccountControl", "garbage"), UNKNOWN)).isNotNull();
        assertThat(disabled(new BasicAttributes(true), UNKNOWN)).isNull();
    }

    @Test
    void anActiveDirectoryLockoutLapsesOnlyWhenAKnownDurationHasPassed() throws Exception {
        String lockedTenMinutesAgo = filetime(NOW.minus(Duration.ofMinutes(10)));
        String lockedAnHourAgo = filetime(NOW.minus(Duration.ofHours(1)));
        DirectoryStanding.Policies thirtyMinutes = policies(value("-18000000000"), null);
        // AD computes the lock itself: UF_LOCKOUT set, or cleared once the lockout lapsed,
        // whatever lockoutTime still says.
        assertThat(disabled(with("msDS-User-Account-Control-Computed", "16", "lockoutTime", lockedTenMinutesAgo), UNKNOWN))
                .contains("locked out");
        assertThat(disabled(with("msDS-User-Account-Control-Computed", "0", "lockoutTime", lockedTenMinutesAgo), UNKNOWN))
                .isNull();
        // Without it: lockoutTime against the domain's lockoutDuration (30 minutes here).
        assertThat(disabled(with("lockoutTime", lockedTenMinutesAgo), thirtyMinutes)).contains("locked out");
        // An hour ago, 30 minutes' lockout: unlocked by itself, and allowed.
        assertThat(disabled(with("lockoutTime", lockedAnHourAgo), thirtyMinutes)).isNull();
        // 0 and "never": until an administrator unlocks it.
        assertThat(disabled(with("lockoutTime", filetime(NOW.minus(Duration.ofDays(30)))),
                policies(value(String.valueOf(Long.MIN_VALUE)), null))).contains("locked out");
        assertThat(disabled(with("lockoutTime", lockedAnHourAgo), policies(value("0"), null))).contains("locked out");
        // No duration to go by - none on the base entry, no base entry, one that does not
        // parse - or a lockoutTime that does not: locked.
        assertThat(disabled(with("lockoutTime", lockedAnHourAgo), UNKNOWN)).contains("locked out");
        assertThat(disabled(with("lockoutTime", lockedAnHourAgo), policies(value(null), null))).contains("locked out");
        assertThat(disabled(with("lockoutTime", lockedAnHourAgo), policies(value("thirty minutes"), null))).contains("locked out");
        assertThat(disabled(with("lockoutTime", "yesterday"), thirtyMinutes)).contains("locked out");
        assertThat(disabled(with("lockoutTime", "0"), UNKNOWN)).isNull();
    }

    @Test
    void aPasswordPolicyLockLapsesOnlyWhenAKnownDurationHasPassed() throws Exception {
        String policy = "cn=default,ou=policies,dc=example,dc=test";
        DirectoryStanding.Policies halfAnHour = policies(null, value("1800"));
        assertThat(disabled(with("pwdAccountLockedTime", DirectoryStanding.PERMANENT_LOCK), UNKNOWN)).contains("locked");
        assertThat(disabled(with("pwdAccountLockedTime", "20261021115000Z", "pwdPolicySubentry", policy), halfAnHour))
                .contains("locked");
        // Locked two hours ago for half an hour: unlocked by itself, and allowed.
        assertThat(disabled(with("pwdAccountLockedTime", "20261021100000Z", "pwdPolicySubentry", policy), halfAnHour))
                .isNull();
        // 0, or a policy without pwdLockoutDuration (whose default is 0): until an administrator unlocks it.
        assertThat(disabled(with("pwdAccountLockedTime", "20261001100000.123Z", "pwdPolicySubentry", policy),
                policies(null, value("0")))).contains("locked");
        assertThat(disabled(with("pwdAccountLockedTime", "20261001100000Z", "pwdPolicySubentry", policy),
                policies(null, value(null), Duration.ofMinutes(30)))).contains("locked");
        // No policy to go by - no pwdPolicySubentry (olcPPolicyDefault), a policy entry that
        // is not there - a duration or a lock time that does not parse: locked.
        assertThat(disabled(with("pwdAccountLockedTime", "20261021100000Z"), halfAnHour)).contains("locked");
        assertThat(disabled(with("pwdAccountLockedTime", "20261021100000Z", "pwdPolicySubentry", policy), UNKNOWN))
                .contains("locked");
        assertThat(disabled(with("pwdAccountLockedTime", "20261021100000Z", "pwdPolicySubentry", policy),
                policies(null, value("half an hour")))).contains("locked");
        assertThat(disabled(with("pwdAccountLockedTime", "earlier", "pwdPolicySubentry", policy), halfAnHour))
                .contains("locked");
    }

    @Test
    void anAssumedDurationIsUsedOnlyWhereNoneCanBeFound() throws Exception {
        String policy = "cn=default,ou=policies,dc=example,dc=test";
        Duration halfAnHour = Duration.ofMinutes(30);
        assertThat(disabled(with("pwdAccountLockedTime", "20261021100000Z"), assuming(halfAnHour))).isNull();
        assertThat(disabled(with("pwdAccountLockedTime", "20261021115000Z"), assuming(halfAnHour))).contains("locked");
        assertThat(disabled(with("pwdAccountLockedTime", "20261021100000Z", "pwdPolicySubentry", policy), assuming(halfAnHour)))
                .isNull();
        assertThat(disabled(with("pwdAccountLockedTime", DirectoryStanding.PERMANENT_LOCK), assuming(halfAnHour)))
                .contains("locked");
        assertThat(disabled(with("lockoutTime", filetime(NOW.minus(Duration.ofHours(1)))), assuming(halfAnHour))).isNull();
        assertThat(disabled(with("lockoutTime", filetime(NOW.minus(Duration.ofMinutes(10)))), assuming(halfAnHour)))
                .contains("locked out");
        // Zero assumes nothing lapses.
        assertThat(disabled(with("pwdAccountLockedTime", "20261001100000Z"), assuming(Duration.ZERO))).contains("locked");
        // The directory's own duration wins over the assumed one.
        assertThat(disabled(with("lockoutTime", filetime(NOW.minus(Duration.ofHours(1)))),
                policies(value("0"), null, halfAnHour))).contains("locked out");
    }

    @Test
    void a389AccountPolicyLockHoldsUntilItsUnlockTime() throws Exception {
        assertThat(disabled(with("accountUnlockTime", "20261021123000Z"), UNKNOWN)).contains("accountUnlockTime");
        assertThat(disabled(with("accountUnlockTime", DirectoryStanding.ADMIN_UNLOCK), UNKNOWN)).contains("accountUnlockTime");
        assertThat(disabled(with("accountUnlockTime", "20261021113000Z"), UNKNOWN)).isNull();
        assertThat(disabled(with("accountUnlockTime", "soon"), UNKNOWN)).contains("accountUnlockTime");
    }

    @Test
    void aPolicyThatCannotBeReadIsAnErrorNotALapse() {
        String policy = "cn=default,ou=policies,dc=example,dc=test";
        assertThatThrownBy(() -> disabled(with("lockoutTime", filetime(NOW.minus(Duration.ofDays(1)))), FAILING))
                .isInstanceOf(NoPermissionException.class);
        assertThatThrownBy(() -> disabled(with("pwdAccountLockedTime", "20261001100000Z", "pwdPolicySubentry", policy), FAILING))
                .isInstanceOf(javax.naming.CommunicationException.class);
    }

    @Test
    void readingThePolicyFromTheDirectory() throws Exception {
        String user = "uid=ada,ou=people,dc=example,dc=test";
        BasicAttributes lockedTwoHoursAgo = with("uid", "ada", "pwdAccountLockedTime", "20261021100000Z",
                "pwdPolicySubentry", "cn=default,ou=policies,dc=example,dc=test");
        BasicAttributes noPolicy = with("uid", "ada", "pwdAccountLockedTime", "20261021100000Z");

        // The service account may not read the policy: unreachable, refused.
        assertThat(standing(lockedTwoHoursAgo, new NoPermissionException("insufficient access"), null).of(user, Duration.ZERO)
                .state()).isEqualTo(DirectoryStanding.State.UNREACHABLE);
        // The connection drops: likewise.
        assertThat(standing(lockedTwoHoursAgo, new javax.naming.CommunicationException("reset"), null).of(user, Duration.ZERO)
                .state()).isEqualTo(DirectoryStanding.State.UNREACHABLE);
        // The policy entry is not there: the user is still there, and locked.
        assertThat(standing(lockedTwoHoursAgo, new NameNotFoundException("cn=default"), null).of(user, Duration.ZERO)
                .state()).isEqualTo(DirectoryStanding.State.DISABLED);
        // Without pwdPolicySubentry: locked, unless an assumed duration has passed.
        assertThat(standing(noPolicy, null, null).of(user, Duration.ZERO).state()).isEqualTo(DirectoryStanding.State.DISABLED);
        assertThat(standing(noPolicy, null, Duration.ofMinutes(30)).of(user, Duration.ZERO).state())
                .isEqualTo(DirectoryStanding.State.ACTIVE);
        // A readable policy of half an hour: lapsed, allowed.
        assertThat(standing(lockedTwoHoursAgo, null, null).of(user, Duration.ZERO).state())
                .isEqualTo(DirectoryStanding.State.ACTIVE);
    }

    /**
     * A directory holding this entry, whose policy read throws {@code policyError} (or answers
     * pwdLockoutDuration 1800).
     */
    private static DirectoryStanding standing(BasicAttributes entry, NamingException policyError, Duration assumed)
            throws Exception {
        DirContext context = mock(DirContext.class);
        when(context.getAttributes(any(Name.class), any(String[].class))).thenAnswer(invocation -> {
            String[] names = invocation.getArgument(1);
            if (Arrays.asList(names).contains("pwdLockoutDuration")) {
                if (policyError != null) {
                    throw policyError;
                }
                return with("pwdLockoutDuration", "1800");
            }
            return entry;
        });
        BaseLdapPathContextSource source = mock(BaseLdapPathContextSource.class);
        when(source.getBaseLdapName()).thenReturn(new LdapName("dc=example,dc=test"));
        when(source.getReadOnlyContext()).thenReturn(context);
        LdapProperties ldap = new LdapProperties();
        ldap.getLockout().setAssumedDuration(assumed);
        SecurityProperties security = new SecurityProperties();
        return new DirectoryStanding(source, security, new DirectoryPrincipalResolver(null, security, null), ldap,
                Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    }

    @Test
    void theConfiguredUidAttributeIsWhatAdministratorsAreMatchedBy() throws Exception {
        SecurityProperties security = new SecurityProperties();
        security.setAdminIdentifiers(List.of("pkiadmin"));
        LdapProperties ldap = new LdapProperties();
        ldap.getUser().setUid("sAMAccountName");
        DirContext context = mock(DirContext.class);
        when(context.getAttributes(any(Name.class), argThat((String[] names) -> Arrays.asList(names).contains("sAMAccountName"))))
                .thenReturn(with("sAMAccountName", "PKIAdmin", "uid", "somebody-else"));
        BaseLdapPathContextSource source = mock(BaseLdapPathContextSource.class);
        when(source.getBaseLdapName()).thenReturn(new LdapName("dc=corp,dc=example"));
        when(source.getReadOnlyContext()).thenReturn(context);
        DirectoryStanding standing = new DirectoryStanding(source, security,
                new DirectoryPrincipalResolver(null, security, null), ldap, Clock.systemUTC());

        DirectoryStanding.Standing admin = standing.of("cn=PKI Admin,ou=staff,dc=corp,dc=example", Duration.ZERO);
        assertThat(admin.state()).isEqualTo(DirectoryStanding.State.ACTIVE);
        assertThat(admin.roles()).contains(DirectoryPrincipalResolver.ROLE_ADMIN);
    }

    @Test
    void aDirectoryThatCannotBeReadRefusesAndIsAskedAgainEveryTime() throws Exception {
        BaseLdapPathContextSource source = mock(BaseLdapPathContextSource.class);
        when(source.getBaseLdapName()).thenReturn(new LdapName("dc=example,dc=test"));
        when(source.getReadOnlyContext()).thenThrow(new org.springframework.ldap.CommunicationException(
                new javax.naming.CommunicationException("connection refused")));
        DirectoryStanding standing = new DirectoryStanding(
                source, new SecurityProperties(), null, new LdapProperties(), Clock.systemUTC());

        DirectoryStanding.Standing first = standing.of("uid=ada,ou=people,dc=example,dc=test", Duration.ofMinutes(5));
        DirectoryStanding.Standing second = standing.of("uid=ada,ou=people,dc=example,dc=test", Duration.ofMinutes(5));
        assertThat(first.state()).isEqualTo(DirectoryStanding.State.UNREACHABLE);
        assertThat(second.state()).isEqualTo(DirectoryStanding.State.UNREACHABLE);
        verify(source, times(2)).getReadOnlyContext();

        assertThat(standing.of("uid=ada,ou=people,dc=elsewhere", Duration.ZERO).state()).isEqualTo(DirectoryStanding.State.GONE);
        assertThat(standing.of(null, Duration.ZERO).state()).isEqualTo(DirectoryStanding.State.GONE);
    }
}

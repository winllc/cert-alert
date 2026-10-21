package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;
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
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.ldap.LdapName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;

/**
 * What makes an account unusable now - and what no longer does once a lockout has lapsed -
 * the uid attribute the deployment configured, and a directory that cannot be read refusing,
 * every time.
 */
class DirectoryStandingTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    /** Neither a domain lockoutDuration nor a password policy can be read. */
    private static final DirectoryStanding.Policies UNREADABLE = policies(null, null);

    private static DirectoryStanding.Policies policies(String domainDuration, String pwdDuration) {
        return new DirectoryStanding.Policies() {
            @Override
            public String domainLockoutDuration() {
                return domainDuration;
            }

            @Override
            public String pwdLockoutDuration(String policyDn) {
                return pwdDuration;
            }
        };
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
        assertThat(disabled(with("nsAccountLock", "TRUE"), UNREADABLE)).contains("nsAccountLock");
        assertThat(disabled(with("nsAccountLock", "false"), UNREADABLE)).isNull();
        assertThat(disabled(with("userAccountControl", "514"), UNREADABLE)).contains("disabled");
        assertThat(disabled(with("userAccountControl", "512"), UNREADABLE)).isNull();
        assertThat(disabled(with("userAccountControl", "garbage"), UNREADABLE)).isNotNull();
        assertThat(disabled(new BasicAttributes(true), UNREADABLE)).isNull();
    }

    @Test
    void anActiveDirectoryLockoutCountsOnlyWhileItLasts() throws Exception {
        String lockedTenMinutesAgo = filetime(NOW.minus(Duration.ofMinutes(10)));
        // AD computes the lock itself: UF_LOCKOUT set, or cleared once the lockout lapsed,
        // whatever lockoutTime still says.
        assertThat(disabled(with("msDS-User-Account-Control-Computed", "16", "lockoutTime", lockedTenMinutesAgo), UNREADABLE))
                .contains("locked out");
        assertThat(disabled(with("msDS-User-Account-Control-Computed", "0", "lockoutTime", lockedTenMinutesAgo),
                policies("-18000000000", null))).isNull();
        // Without it: lockoutTime against the domain's lockoutDuration (30 minutes here).
        assertThat(disabled(with("lockoutTime", lockedTenMinutesAgo), policies("-18000000000", null))).contains("locked out");
        // An hour ago, 30 minutes' lockout: unlocked by itself, and allowed.
        assertThat(disabled(with("lockoutTime", filetime(NOW.minus(Duration.ofHours(1)))), policies("-18000000000", null)))
                .isNull();
        // Until an administrator unlocks it.
        assertThat(disabled(with("lockoutTime", filetime(NOW.minus(Duration.ofDays(30)))),
                policies(String.valueOf(Long.MIN_VALUE), null))).contains("locked out");
        // No duration to read: lockoutTime alone, which outlives the lockout, does not lock.
        assertThat(disabled(with("lockoutTime", lockedTenMinutesAgo), UNREADABLE)).isNull();
        assertThat(disabled(with("lockoutTime", "0"), policies("-18000000000", null))).isNull();
    }

    @Test
    void aPasswordPolicyLockCountsOnlyWhileItLasts() throws Exception {
        String policy = "cn=default,ou=policies,dc=example,dc=test";
        assertThat(disabled(with("pwdAccountLockedTime", DirectoryStanding.PERMANENT_LOCK), UNREADABLE)).contains("locked");
        assertThat(disabled(with("pwdAccountLockedTime", "20261021115000Z", "pwdPolicySubentry", policy), policies(null, "1800")))
                .contains("locked");
        // Locked two hours ago for half an hour: unlocked by itself, and allowed.
        assertThat(disabled(with("pwdAccountLockedTime", "20261021100000Z", "pwdPolicySubentry", policy), policies(null, "1800")))
                .isNull();
        // 0: until an administrator unlocks it.
        assertThat(disabled(with("pwdAccountLockedTime", "20261001100000.123Z", "pwdPolicySubentry", policy), policies(null, "0")))
                .contains("locked");
        // No policy to read: only the permanent lock counts.
        assertThat(disabled(with("pwdAccountLockedTime", "20261021115000Z", "pwdPolicySubentry", policy), UNREADABLE)).isNull();
        assertThat(disabled(with("pwdAccountLockedTime", "20261021115000Z"), policies(null, "1800"))).isNull();
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

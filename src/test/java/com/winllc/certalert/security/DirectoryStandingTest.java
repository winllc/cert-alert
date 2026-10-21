package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import javax.naming.directory.BasicAttributes;
import javax.naming.ldap.LdapName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;

/** What makes an account unusable, and a directory that cannot be read refusing, every time. */
class DirectoryStandingTest {

    private static BasicAttributes with(String name, String value) {
        BasicAttributes attributes = new BasicAttributes(true);
        attributes.put(name, value);
        return attributes;
    }

    @Test
    void everyKindOfLockIsRead() throws Exception {
        assertThat(DirectoryStanding.disabledBy(with("nsAccountLock", "TRUE"))).contains("nsAccountLock");
        assertThat(DirectoryStanding.disabledBy(with("nsAccountLock", "false"))).isNull();
        assertThat(DirectoryStanding.disabledBy(with("userAccountControl", "514"))).contains("disabled");
        assertThat(DirectoryStanding.disabledBy(with("userAccountControl", "512"))).isNull();
        assertThat(DirectoryStanding.disabledBy(with("userAccountControl", "garbage"))).isNotNull();
        assertThat(DirectoryStanding.disabledBy(with("lockoutTime", "133420000000000000"))).contains("locked out");
        assertThat(DirectoryStanding.disabledBy(with("lockoutTime", "0"))).isNull();
        assertThat(DirectoryStanding.disabledBy(with("pwdAccountLockedTime", "000001010000Z"))).contains("locked");
        assertThat(DirectoryStanding.disabledBy(new BasicAttributes(true))).isNull();
    }

    @Test
    void aDirectoryThatCannotBeReadRefusesAndIsAskedAgainEveryTime() throws Exception {
        BaseLdapPathContextSource source = mock(BaseLdapPathContextSource.class);
        when(source.getBaseLdapName()).thenReturn(new LdapName("dc=example,dc=test"));
        when(source.getReadOnlyContext()).thenThrow(new org.springframework.ldap.CommunicationException(
                new javax.naming.CommunicationException("connection refused")));
        DirectoryStanding standing = new DirectoryStanding(source, new SecurityProperties(), null, Clock.systemUTC());

        DirectoryStanding.Standing first = standing.of("uid=ada,ou=people,dc=example,dc=test", Duration.ofMinutes(5));
        DirectoryStanding.Standing second = standing.of("uid=ada,ou=people,dc=example,dc=test", Duration.ofMinutes(5));
        assertThat(first.state()).isEqualTo(DirectoryStanding.State.UNREACHABLE);
        assertThat(second.state()).isEqualTo(DirectoryStanding.State.UNREACHABLE);
        verify(source, times(2)).getReadOnlyContext();

        assertThat(standing.of("uid=ada,ou=people,dc=elsewhere", Duration.ZERO).state()).isEqualTo(DirectoryStanding.State.GONE);
        assertThat(standing.of(null, Duration.ZERO).state()).isEqualTo(DirectoryStanding.State.GONE);
    }
}

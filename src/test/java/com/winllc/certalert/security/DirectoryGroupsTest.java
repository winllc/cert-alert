package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import javax.naming.Name;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.ldap.LdapName;
import org.junit.jupiter.api.Test;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;

/** A directory that could not be read is not a person with no groups, for longer than a few seconds. */
class DirectoryGroupsTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");

    private static final String ADMINS = "cn=pki-admins,ou=groups,dc=example,dc=test";

    @Test
    void aFailedReadIsTriedAgainSoonNotKeptForTheWholeCacheTime() throws Exception {
        Instant[] now = {NOW};
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        DirContext context = mock(DirContext.class);
        when(context.getAttributes(any(Name.class), any(String[].class)))
                .thenThrow(new javax.naming.CommunicationException("connection refused"))
                .thenReturn(new BasicAttributes("memberOf", ADMINS, true));
        BaseLdapPathContextSource source = mock(BaseLdapPathContextSource.class);
        when(source.getBaseLdapName()).thenReturn(new LdapName("dc=example,dc=test"));
        when(source.getReadOnlyContext()).thenReturn(context);
        SecurityProperties security = new SecurityProperties();
        security.getGroups().setAdmin(List.of(ADMINS));
        DirectoryGroups groups = new DirectoryGroups(source, security, clock);
        String dn = "uid=ada,ou=people,dc=example,dc=test";

        assertThat(groups.of(dn)).isEmpty();
        // Still within the few seconds a failure stands: not read again.
        now[0] = NOW.plusSeconds(5);
        assertThat(groups.of(dn)).isEmpty();
        // Well inside the five minutes a good answer is kept, the directory is asked again.
        now[0] = NOW.plus(DirectoryGroups.UNREADABLE_REMEMBERED).plusSeconds(1);
        assertThat(groups.of(dn)).containsExactly(DirectoryPrincipalResolver.normalise(ADMINS));
    }
}

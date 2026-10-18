package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryUserRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * An existing configuration that lists administrators by address still starts, and is told
 * at startup which entries are ignored and what to list instead.
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminIdentifierCheckTest {

    private DirectoryUserRepository users;
    private SecurityProperties properties;
    private AdminIdentifierCheck check;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        users = mock(DirectoryUserRepository.class);
        when(users.findByIdentifier(anyString())).thenReturn(List.of());
        properties = new SecurityProperties();
        DirectoryPrincipalResolver resolver =
                new DirectoryPrincipalResolver(users, properties, mock(ObjectProvider.class));
        check = new AdminIdentifierCheck(resolver, users, properties);
    }

    @Test
    void anAddressIsNamedWithTheUidAndDnTheCacheHoldsForIt(CapturedOutput output) {
        properties.setAdminIdentifiers(List.of("jdoe", "Alice@Example.gov"));
        DirectoryUser alice = new DirectoryUser("uid=alice,ou=people,dc=example,dc=gov");
        ReflectionTestUtils.setField(alice, "id", 1L);
        alice.setUid("alice");
        alice.setIcEmail("alice@example.gov");
        when(users.findByIdentifier("alice@example.gov")).thenReturn(List.of(alice));

        check.warnAboutAddresses();

        assertThat(output.getOut())
                .contains("lists 'Alice@Example.gov', an e-mail address, and it is ignored")
                .contains("uid 'alice' (uid=alice,ou=people,dc=example,dc=gov): list 'alice', or that DN, instead")
                .doesNotContain("names no uid or DN");
    }

    @Test
    void anAddressTheCacheDoesNotHoldSaysWhatToListInstead(CapturedOutput output) {
        properties.setAdminIdentifiers(List.of("pki-admin@example.gov"));

        check.warnAboutAddresses();

        assertThat(output.getOut())
                .contains("lists 'pki-admin@example.gov', an e-mail address, and it is ignored")
                .contains("List that administrator's uid, or the DN of their entry, instead")
                .contains("names no uid or DN, so nobody signs in as an administrator");
    }

    @Test
    void uidsAndDnsAreSaidNothingAbout(CapturedOutput output) {
        properties.setAdminIdentifiers(List.of("jdoe", "uid=ops@example.gov,ou=people,dc=example,dc=gov"));

        check.warnAboutAddresses();

        assertThat(output.getOut()).doesNotContain("admin-identifiers");
    }
}

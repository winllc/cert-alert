package com.winllc.certalert.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.support.TestCertificates;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * What decides who somebody is and whether they administer: what the directory says
 * identifies them, and never a name.
 */
class DirectoryPrincipalResolverTest {

    private static final String ADMIN = "pki-admin@example.gov";

    private DirectoryUserRepository repository;
    private SecurityProperties properties;
    private DirectoryPrincipalResolver resolver;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(DirectoryUserRepository.class);
        properties = new SecurityProperties();
        properties.setAdminIdentifiers(List.of(ADMIN, "jdoe", "UID=Root, OU=People, DC=Example, DC=Test"));
        resolver = new DirectoryPrincipalResolver(repository, properties, mock(ObjectProvider.class));
    }

    @Test
    void aCommonDisplayOrPreferredNameNamedAsAnAdministratorGrantsNothing() {
        DirectoryUser user = user(1L, "uid=pat,ou=people,dc=example,dc=test", "pat", "pat@example.gov");
        user.setCommonName(ADMIN);
        user.setDisplayName("jdoe");
        user.setPreferredName(ADMIN);
        user.refreshIdentifiers("pat@example.gov");

        assertThat(roles(resolver.principalFor(user, DirectoryPrincipal.AuthenticationMethod.LDAP)))
                .containsExactly(DirectoryPrincipalResolver.ROLE_USER);
    }

    @Test
    void anAddressTheDirectoryPublishesGrantsIt() {
        DirectoryUser user = user(2L, "uid=pki,ou=people,dc=example,dc=test", "pki", null);
        user.setNiprnetEmail("PKI-Admin@example.gov");
        user.refreshIdentifiers(null);

        assertThat(roles(resolver.principalFor(user, DirectoryPrincipal.AuthenticationMethod.X509)))
                .contains(DirectoryPrincipalResolver.ROLE_ADMIN);
    }

    @Test
    void anAddressFromTheDeploymentsOwnAttributesGrantsIt() {
        DirectoryUser user = user(3L, "uid=other,ou=people,dc=example,dc=test", "other", "other@example.gov");
        when(repository.findAdditionalEmailsById(3L)).thenReturn(Set.of(ADMIN));

        assertThat(roles(resolver.principalFor(user, DirectoryPrincipal.AuthenticationMethod.LDAP)))
                .contains(DirectoryPrincipalResolver.ROLE_ADMIN);
    }

    @Test
    void aUidGrantsIt() {
        DirectoryUser user = user(4L, "uid=jdoe,ou=people,dc=example,dc=test", "JDoe", "john.doe@example.gov");

        assertThat(roles(resolver.principalFor(user, DirectoryPrincipal.AuthenticationMethod.LDAP)))
                .contains(DirectoryPrincipalResolver.ROLE_ADMIN);
    }

    /** However the DN was spaced and cased in the configuration. */
    @Test
    void theDnOfTheEntryGrantsIt() {
        DirectoryUser user = user(5L, "uid=root,ou=people,dc=example,dc=test", "root", "root@example.gov");

        assertThat(roles(resolver.principalFor(user, DirectoryPrincipal.AuthenticationMethod.LDAP)))
                .contains(DirectoryPrincipalResolver.ROLE_ADMIN);
        assertThat(roles(resolver.unknownPrincipal(
                        "root", "uid=root,ou=people,dc=example,dc=test", List.of(),
                        DirectoryPrincipal.AuthenticationMethod.LDAP)))
                .contains(DirectoryPrincipalResolver.ROLE_ADMIN);
    }

    @Test
    void somebodyTheCacheDoesNotHoldIsJudgedByWhatTheyBoundAs() {
        DirectoryPrincipal principal = resolver.unknownPrincipal(
                "newcomer", "uid=newcomer,ou=people,dc=example,dc=test", List.of("newcomer", "new@example.gov"),
                DirectoryPrincipal.AuthenticationMethod.LDAP);

        assertThat(principal.getDirectoryUserId()).isNull();
        assertThat(principal.getDirectoryDn()).isEqualTo("uid=newcomer,ou=people,dc=example,dc=test");
        assertThat(roles(principal)).containsExactly(DirectoryPrincipalResolver.ROLE_USER);

        assertThat(roles(resolver.unknownPrincipal(
                        "newcomer", "uid=newcomer,ou=people,dc=example,dc=test", List.of(ADMIN),
                        DirectoryPrincipal.AuthenticationMethod.LDAP)))
                .contains(DirectoryPrincipalResolver.ROLE_ADMIN);
    }

    /** The certificate fallback: a person is found by their uid or an address, not their name. */
    @Test
    void aCertificateIsMatchedToAPersonByTheirUidOrAddressOnly() {
        DirectoryUser named = user(6L, "uid=pat,ou=people,dc=example,dc=test", "pat", "pat@example.gov");
        named.setCommonName("Pat Doe");
        named.refreshIdentifiers("pat@example.gov");
        when(repository.findByIdentifier("pat doe")).thenReturn(List.of(named));
        when(repository.findByIdentifier("pat@example.gov")).thenReturn(List.of(named));

        assertThat(resolver.findByDirectoryIdentifier(List.of("pat doe"))).isEmpty();
        assertThat(resolver.findByDirectoryIdentifier(List.of("pat doe", "pat@example.gov"))).contains(named);
    }

    /** Values naming two different people name neither. */
    @Test
    void aCertificateNamingTwoPeopleIsNeither() {
        DirectoryUser one = user(7L, "uid=one,ou=people,dc=example,dc=test", "one", "shared@example.gov");
        DirectoryUser two = user(8L, "uid=two,ou=people,dc=example,dc=test", "two", "two@example.gov");
        when(repository.findByIdentifier("shared@example.gov")).thenReturn(List.of(one));
        when(repository.findByIdentifier("two")).thenReturn(List.of(two));

        assertThat(resolver.findByDirectoryIdentifier(List.of("shared@example.gov", "two"))).isEmpty();
    }

    /** A certificate's common name is not one of the values it is matched on at all. */
    @Test
    void aCertificatesCommonNameIsNotAnIdentifier() {
        Instant now = Instant.now();
        assertThat(X509Identifiers.of(TestCertificates.certificate(
                        "Pat Doe", now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(30)))))
                .isEmpty();
    }

    private static DirectoryUser user(Long id, String dn, String uid, String icEmail) {
        DirectoryUser user = new DirectoryUser(dn);
        ReflectionTestUtils.setField(user, "id", id);
        user.setUid(uid);
        user.setIcEmail(icEmail);
        user.refreshIdentifiers(icEmail);
        return user;
    }

    private static List<String> roles(DirectoryPrincipal principal) {
        return principal.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }
}

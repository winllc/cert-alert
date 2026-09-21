package com.winllc.certalert.ldap;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestCertificates;
import java.time.Duration;
import java.util.List;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A directory that publishes the certificate under the plain attribute name.
 *
 * <p>RFC 4522 says a certificate is transferred in binary, and the way a client insists on
 * that is to ask for {@code userCertificate;binary}. Not every server has such an
 * attribute to give: a virtual directory assembling one from another store presents plain
 * {@code userCertificate}, and a search naming the binary option does not match it. The
 * entry then syncs with every other attribute populated and no certificates at all, which
 * is what was seen against Radiant Logic FID 7.4.
 *
 * <p>This runs with the option off, which is what such a directory needs.
 */
@SpringBootTest(properties = "cert-alert.ldap.binary-certificate-option=false")
@ActiveProfiles("test")
class UnqualifiedCertificateAttributeTest {

    private static EmbeddedDirectory directory;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private DirectoryUserRepository userRepository;

    @Autowired
    private DirectoryEntryConnection connection;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void startDirectory() {
        directory = new EmbeddedDirectory();
    }

    @AfterAll
    static void stopDirectory() {
        if (directory != null) {
            directory.close();
        }
    }

    @DynamicPropertySource
    static void directoryProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", () -> directory.url());
        registry.add("spring.ldap.base", () -> EmbeddedDirectory.BASE_DN);
        registry.add("cert-alert.ldap.user.search-base", () -> "ou=people");
        registry.add("cert-alert.ldap.server.search-base", () -> "ou=servers");
    }

    @BeforeEach
    void resetDatabase() {
        userRepository.deleteAll();
    }

    /**
     * The failure itself, at the protocol level, so what the toggle is for is written down
     * rather than assumed. Both requests go to a real LDAP server against one entry; only
     * the attribute name differs.
     */
    @Test
    void aBinaryRequestDoesNotMatchAPlainlyStoredCertificate() {
        String dn = directory.addUserWithUnqualifiedCertificate(
                "vdir", "Vee Dir", "vee.dir@example.gov",
                TestCertificates.expiringIn("vdir", Duration.ofDays(120)));

        // objectClass comes back either way; the certificate is the question.
        assertThat(certificateNames(dn, "userCertificate;binary"))
                .as("what this application used to ask for")
                .isEmpty();
        // Lowercased: JNDI reports the id as it pleases, which is why LdapAttributes
        // matches on base name and ignores case.
        assertThat(certificateNames(dn, "userCertificate"))
                .as("and what the entry actually holds")
                .containsExactly("usercertificate");
    }

    @Test
    void soTheSweepCachesNothingUntilTheOptionIsTurnedOff() {
        directory.addUserWithUnqualifiedCertificate(
                "plain", "Plain Holder", "plain.holder@example.gov",
                TestCertificates.expiringIn("plain", Duration.ofDays(200)));

        syncService.syncUsers();

        DirectoryUser user = userByDn("uid=plain," + EmbeddedDirectory.PEOPLE_DN);
        // The entry synced either way; the certificate is what the option decides.
        assertThat(user.getDisplayName()).isEqualTo("Plain Holder");
        assertThat(user.getCertificateCount()).isEqualTo(1);
        assertThat(user.getCertificates().getFirst().getSubjectDn()).isEqualTo("CN=plain");
        // Parsed, so the bytes survived the trip rather than arriving as mangled text.
        assertThat(user.getCertificates().getFirst().getSha256Fingerprint()).hasSize(64);
    }

    /** An entry that does carry the binary option still reads, so the toggle costs nothing. */
    @Test
    void andAnEntryStoredTheUsualWayIsStillRead() {
        directory.addUser(
                "qualified", "Quinn Ified", "quinn.ified@example.gov",
                TestCertificates.expiringIn("qualified", Duration.ofDays(150)));

        syncService.syncUsers();

        DirectoryUser user = userByDn("uid=qualified," + EmbeddedDirectory.PEOPLE_DN);
        assertThat(user.getCertificateCount()).isEqualTo(1);
        assertThat(user.getCertificates().getFirst().getSubjectDn()).isEqualTo("CN=qualified");
    }

    /** Read inside a transaction, because the certificates are a lazy collection. */
    private DirectoryUser userByDn(String dn) {
        return transactionTemplate.execute(status -> {
            DirectoryUser user = userRepository.findByDn(dn).orElseThrow();
            user.getCertificates().size();
            return user;
        });
    }

    /** The certificate attributes one entry returns when asked for exactly this name. */
    private List<String> certificateNames(String dn, String requested) {
        return attributeNames(dn, requested).stream()
                .filter(id -> id.toLowerCase(java.util.Locale.ROOT).startsWith("usercertificate"))
                .toList();
    }

    /** Every attribute name one entry returns when asked for exactly this one. */
    private List<String> attributeNames(String dn, String requested) {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.OBJECT_SCOPE);
        controls.setReturningAttributes(new String[] {requested});
        List<List<String>> found = connection.template().search(dn, "(objectClass=*)", controls,
                (ContextMapper<List<String>>) ctx -> {
                    Attributes attributes = ((DirContextOperations) ctx).getAttributes();
                    List<String> ids = new java.util.ArrayList<>();
                    var all = attributes.getIDs();
                    try {
                        while (all.hasMore()) {
                            ids.add(all.next());
                        }
                    } catch (javax.naming.NamingException e) {
                        throw new IllegalStateException(e);
                    }
                    return ids;
                });
        return found.isEmpty() ? List.of() : found.getFirst();
    }
}

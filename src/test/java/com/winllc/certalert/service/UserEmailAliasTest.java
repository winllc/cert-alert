package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.UserEmailAlias;
import com.winllc.certalert.repository.AuditEventRepository;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectorySpecifications;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.UserEmailAliasRepository;
import com.winllc.certalert.support.EmbeddedDirectory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The addresses a person answers to beyond the directory's, and what they are for: binding
 * a person to a server whose {@code serverPOC} the directory's addresses do not reach.
 */
@SpringBootTest
@ActiveProfiles("test")
class UserEmailAliasTest {

    private static EmbeddedDirectory directory;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private UserEmailAliasService aliasService;

    @Autowired
    private UserEmailAliasRepository aliasRepository;

    @Autowired
    private DirectoryUserRepository userRepository;

    @Autowired
    private DirectoryServerRepository serverRepository;

    @Autowired
    private AuditEventRepository auditRepository;

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
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", () -> directory.url());
        registry.add("spring.ldap.base", () -> EmbeddedDirectory.BASE_DN);
        registry.add("cert-alert.ldap.user.search-base", () -> "ou=people");
        registry.add("cert-alert.ldap.server.search-base", () -> "ou=servers");
    }

    @BeforeEach
    void reset() {
        auditRepository.deleteAll();
        serverRepository.deleteAll();
        userRepository.deleteAll();
    }

    /**
     * The case the whole feature exists for: a server named after a team's list, which the
     * directory publishes for nobody, so the server has no contact at all until somebody
     * says who is on the list.
     */
    @Test
    void anAddressAddedHereBindsAServerToThePersonWhoAnswersIt() {
        String userDn = directory.addUser("lister", "Lee Ster", "lee.ster@example.gov");
        String serverDn = directory.addServer(
                "listed", "https://listed.example.gov", new String[] {"platform-team@example.gov"});
        syncService.syncUsers();
        syncService.syncServers();

        Long userId = userRepository.findByDn(userDn).orElseThrow().getId();
        assertThat(serversFor(userId)).as("before the list is claimed").isEmpty();

        aliasService.add(userId, "platform-team@example.gov", UserEmailAlias.Kind.GROUP, "Platform team", "alice");

        assertThat(serversFor(userId)).extracting(DirectoryServer::getDn).containsExactly(serverDn);
    }

    /** A list is a list: everybody on it is a contact for the servers named after it. */
    @Test
    void aGroupAddressBindsEveryoneWhoAnswersIt() {
        String firstDn = directory.addUser("first", "First Person", "first@example.gov");
        String secondDn = directory.addUser("second", "Second Person", "second@example.gov");
        String serverDn = directory.addServer("shared", "https://shared.example.gov", new String[] {"ops@example.gov"});
        syncService.syncUsers();
        syncService.syncServers();

        Long firstId = userRepository.findByDn(firstDn).orElseThrow().getId();
        Long secondId = userRepository.findByDn(secondDn).orElseThrow().getId();
        aliasService.add(firstId, "ops@example.gov", UserEmailAlias.Kind.GROUP, "Ops list", "alice");
        aliasService.add(secondId, "ops@example.gov", UserEmailAlias.Kind.GROUP, "Ops list", "alice");

        assertThat(serversFor(firstId)).extracting(DirectoryServer::getDn).containsExactly(serverDn);
        assertThat(serversFor(secondId)).extracting(DirectoryServer::getDn).containsExactly(serverDn);
        assertThat(aliasRepository.findUserIdsByAddress("ops@example.gov"))
                .containsExactlyInAnyOrder(firstId, secondId);
    }

    /**
     * A sweep rebuilds each person's identifiers from the directory. An address added here
     * has to come back with them, or it would bind servers until the next sweep and no
     * longer.
     */
    @Test
    void anAddedAddressSurvivesASweep() {
        String userDn = directory.addUser("surviving", "Sue Viving", "sue@example.gov");
        syncService.syncUsers();
        Long userId = userRepository.findByDn(userDn).orElseThrow().getId();
        aliasService.add(userId, "old.address@example.gov", UserEmailAlias.Kind.PERSONAL, null, "alice");

        // Twice, because the second sweep is the one that reads what the first wrote.
        syncService.syncUsers();
        syncService.syncUsers();

        assertThat(userRepository.findIdentifiersById(userId))
                .contains("old.address@example.gov", "sue@example.gov", "sue viving");
        assertThat(aliasService.list(userId)).hasSize(1);
    }

    /**
     * Removing an address that the directory also publishes must not unbind the person from
     * it: the identifier set is rebuilt from both sources rather than having one value
     * struck out of it.
     */
    @Test
    void removingAnAddressTheDirectoryAlsoPublishesLeavesItInPlace() {
        String userDn = directory.addUser("overlapping", "Over Lapping", "over@example.gov");
        syncService.syncUsers();
        Long userId = userRepository.findByDn(userDn).orElseThrow().getId();

        var alias = aliasService.add(userId, "over@example.gov", UserEmailAlias.Kind.PERSONAL, null, "alice");
        aliasService.remove(userId, alias.getId());

        assertThat(userRepository.findIdentifiersById(userId)).contains("over@example.gov");
    }

    @Test
    void removingAnAddressTakesTheServersWithIt() {
        String userDn = directory.addUser("temporary", "Tem Porary", "tem@example.gov");
        String serverDn = directory.addServer(
                "borrowed", "https://borrowed.example.gov", new String[] {"borrowed-list@example.gov"});
        syncService.syncUsers();
        syncService.syncServers();
        Long userId = userRepository.findByDn(userDn).orElseThrow().getId();

        var alias = aliasService.add(userId, "borrowed-list@example.gov", UserEmailAlias.Kind.GROUP, null, "alice");
        assertThat(serversFor(userId)).extracting(DirectoryServer::getDn).containsExactly(serverDn);

        aliasService.remove(userId, alias.getId());

        assertThat(serversFor(userId)).isEmpty();
    }

    @Test
    void refusesADuplicateAndSomethingThatIsNotAnAddress() {
        String userDn = directory.addUser("picky", "Pick Y", "picky@example.gov");
        syncService.syncUsers();
        Long userId = userRepository.findByDn(userDn).orElseThrow().getId();
        aliasService.add(userId, "extra@example.gov", UserEmailAlias.Kind.PERSONAL, null, "alice");

        assertThatThrownBy(() ->
                        aliasService.add(userId, "EXTRA@example.gov", UserEmailAlias.Kind.PERSONAL, null, "alice"))
                .isInstanceOf(ContactAlreadyExistsException.class);
        assertThatThrownBy(() -> aliasService.add(userId, "the team", UserEmailAlias.Kind.GROUP, null, "alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addingAndRemovingAnAddressIsRecordedAgainstThePerson() {
        String userDn = directory.addUser("audited", "Aud Ited", "aud@example.gov");
        syncService.syncUsers();
        Long userId = userRepository.findByDn(userDn).orElseThrow().getId();

        var alias = aliasService.add(userId, "list@example.gov", UserEmailAlias.Kind.GROUP, "A list", "alice");
        aliasService.remove(userId, alias.getId());

        List<AuditEvent> history = auditRepository
                .findBySubjectTypeAndSubjectIdOrderByOccurredAtDescIdDesc(OwnerType.USER, userId, PageRequest.of(0, 20))
                .getContent();
        assertThat(history).extracting(AuditEvent::getAction)
                .containsExactly(AuditAction.ADDRESS_REMOVED, AuditAction.ADDRESS_ADDED, AuditAction.ENTRY_DISCOVERED);
        assertThat(history.get(1).getSummary()).contains("group").contains("list@example.gov");
        assertThat(history.get(1).getActor()).isEqualTo("alice");
    }

    private List<DirectoryServer> serversFor(Long userId) {
        Set<String> identifiers = userRepository.findIdentifiersById(userId);
        return serverRepository.findAll(DirectorySpecifications.pointOfContactOf(userId, identifiers));
    }
}

package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.Project;
import com.winllc.certalert.repository.AuditEventRepository;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectorySpecifications;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.ProjectRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/** Grouping people and servers into the thing they are actually for. */
@SpringBootTest
@ActiveProfiles("test")
class ProjectTest {

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private DirectoryUserRepository users;

    @Autowired
    private DirectoryServerRepository servers;

    @Autowired
    private AuditEventRepository auditRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Long aliceId;
    private Long bobId;
    private Long webId;
    private Long dbId;

    @BeforeEach
    void seed() {
        projects.deleteAll();
        auditRepository.deleteAll();
        servers.deleteAll();
        users.deleteAll();

        aliceId = users.save(user("uid=alice,ou=people", "alice", "Alice Archer")).getId();
        bobId = users.save(user("uid=bob,ou=people", "bob", "Bob Baker")).getId();
        webId = servers.save(server("cn=web01,ou=servers", "web01")).getId();
        dbId = servers.save(server("cn=db01,ou=servers", "db01")).getId();
    }

    @Test
    void createsAProjectAndPutsPeopleAndServersInIt() {
        Project project = projectService.create("Payroll migration", "Moving payroll off the old host", "alice");

        projectService.addMember(project.getId(), aliceId);
        projectService.addMember(project.getId(), bobId);
        projectService.addServer(project.getId(), webId);

        Project loaded = transactionTemplate.execute(status -> {
            Project found = projectService.get(project.getId());
            found.getMembers().size();
            found.getServers().size();
            return found;
        });
        assertThat(loaded.getName()).isEqualTo("Payroll migration");
        assertThat(loaded.getCreatedBy()).isEqualTo("alice");
        assertThat(loaded.getMembers()).extracting(DirectoryUser::getUid).containsExactlyInAnyOrder("alice", "bob");
        assertThat(loaded.getServers()).extracting(DirectoryServer::getCommonName).containsExactly("web01");
    }

    @Test
    void refusesTwoProjectsOfTheSameNameAndOneWithNone() {
        projectService.create("Payroll", null, "alice");

        assertThatThrownBy(() -> projectService.create("payroll", null, "alice"))
                .isInstanceOf(ContactAlreadyExistsException.class);
        assertThatThrownBy(() -> projectService.create("  ", null, "alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addingSomebodyTwiceChangesNothing() {
        Project project = projectService.create("Idempotent", null, "alice");

        projectService.addMember(project.getId(), aliceId);
        projectService.addMember(project.getId(), aliceId);

        assertThat(membersOf(project.getId())).hasSize(1);
        // And only the first is worth recording.
        assertThat(auditFor(OwnerType.USER, aliceId)).extracting(AuditEvent::getAction)
                .containsExactly(AuditAction.PROJECT_JOINED);
    }

    @Test
    void takingSomethingOutOfAProjectLeavesTheEntryAlone() {
        Project project = projectService.create("Temporary", null, "alice");
        projectService.addServer(project.getId(), webId);

        projectService.removeServer(project.getId(), webId);

        assertThat(serversOf(project.getId())).isEmpty();
        assertThat(servers.findById(webId)).as("the server is the directory's, not the project's").isPresent();
        assertThat(auditFor(OwnerType.SERVER, webId)).extracting(AuditEvent::getAction)
                .containsExactly(AuditAction.PROJECT_LEFT, AuditAction.PROJECT_JOINED);
    }

    /** The grouping goes; what was grouped does not. */
    @Test
    void deletingAProjectLeavesItsMembersInTheDirectory() {
        Project project = projectService.create("Doomed", null, "alice");
        projectService.addMember(project.getId(), aliceId);
        projectService.addServer(project.getId(), webId);

        projectService.delete(project.getId());

        assertThat(projects.findById(project.getId())).isEmpty();
        assertThat(users.findById(aliceId)).isPresent();
        assertThat(servers.findById(webId)).isPresent();
    }

    /** What the project is for, once it exists: the tables narrowed to it. */
    @Test
    void theTablesCanBeNarrowedToAProject() {
        Project project = projectService.create("Filtered", null, "alice");
        projectService.addMember(project.getId(), aliceId);
        projectService.addServer(project.getId(), dbId);

        assertThat(users.findAll(DirectorySpecifications.inProject(project.getId(), "members")))
                .extracting(DirectoryUser::getUid)
                .containsExactly("alice");
        assertThat(servers.findAll(DirectorySpecifications.inProject(project.getId(), "servers")))
                .extracting(DirectoryServer::getCommonName)
                .containsExactly("db01");
        // No project asked for means no narrowing, rather than nothing at all.
        assertThat(users.findAll(DirectorySpecifications.inProject(null, "members"))).hasSize(2);
    }

    @Test
    void anEntryKnowsWhatProjectsItIsIn() {
        Project first = projectService.create("First", null, "alice");
        Project second = projectService.create("Second", null, "alice");
        projectService.addMember(first.getId(), aliceId);
        projectService.addMember(second.getId(), aliceId);
        projectService.addServer(second.getId(), webId);

        assertThat(projectService.forUser(aliceId)).extracting(Project::getName).containsExactly("First", "Second");
        assertThat(projectService.forServer(webId)).extracting(Project::getName).containsExactly("Second");
        assertThat(projectService.forUser(bobId)).isEmpty();
    }

    @Test
    void renamingKeepsTheMembership() {
        Project project = projectService.create("Before", "First description", "alice");
        projectService.addMember(project.getId(), aliceId);

        projectService.rename(project.getId(), "After", "Second description");

        Project loaded = projectService.get(project.getId());
        assertThat(loaded.getName()).isEqualTo("After");
        assertThat(loaded.getDescription()).isEqualTo("Second description");
        assertThat(membersOf(project.getId())).hasSize(1);
    }

    private List<DirectoryUser> membersOf(Long projectId) {
        return transactionTemplate.execute(status -> List.copyOf(projectService.get(projectId).getMembers()));
    }

    private List<DirectoryServer> serversOf(Long projectId) {
        return transactionTemplate.execute(status -> List.copyOf(projectService.get(projectId).getServers()));
    }

    private List<AuditEvent> auditFor(OwnerType type, Long id) {
        return auditRepository
                .findBySubjectTypeAndSubjectIdOrderByOccurredAtDescIdDesc(type, id, PageRequest.of(0, 20))
                .getContent();
    }

    private DirectoryUser user(String dn, String uid, String displayName) {
        DirectoryUser user = new DirectoryUser(dn);
        user.setUid(uid);
        user.setDisplayName(displayName);
        user.refreshIdentifiers(uid + "@example.gov");
        user.markSynced(Instant.now());
        return user;
    }

    private DirectoryServer server(String dn, String commonName) {
        DirectoryServer server = new DirectoryServer(dn);
        server.setCommonName(commonName);
        server.markSynced(Instant.now());
        return server;
    }
}

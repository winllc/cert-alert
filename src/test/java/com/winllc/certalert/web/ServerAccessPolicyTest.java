package com.winllc.certalert.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.ProjectRepository;
import com.winllc.certalert.repository.ServerContactRepository;
import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.security.ServerAccessPolicy;
import com.winllc.certalert.service.ProjectService;
import com.winllc.certalert.service.ServerContactService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Who may manage a server's points of contact.
 *
 * <p>The people who know who belongs on that list are the people already on it and the
 * people who own the project the server is part of. Waiting on an administrator to add a
 * colleague is how a contact list goes stale, and a stale list is a certificate nobody is
 * told about - so association grants it, one server at a time.
 */
@SpringBootTest
@ActiveProfiles("test")
class ServerAccessPolicyTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ServerAccessPolicy policy;

    @Autowired
    private DirectoryUserRepository users;

    @Autowired
    private DirectoryServerRepository servers;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ServerContactService contactService;

    @Autowired
    private ServerContactRepository contacts;

    private MockMvc mockMvc;
    private Long publishedId;
    private Long addedId;
    private Long strangerId;
    private Long theirServerId;
    private Long projectServerId;
    private Long otherServerId;

    @BeforeEach
    void seed() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        projects.deleteAll();
        contacts.deleteAll();
        servers.deleteAll();
        users.deleteAll();

        // Named on the server by the directory, added to it here, and neither.
        publishedId = users.save(user("uid=published,ou=people", "published", "Pub Lished")).getId();
        addedId = users.save(user("uid=added,ou=people", "added", "Add Ed")).getId();
        strangerId = users.save(user("uid=stranger,ou=people", "stranger", "Stran Ger")).getId();

        theirServerId = servers.save(server("cn=theirs,ou=servers", "theirs", "published@example.gov")).getId();
        projectServerId = servers.save(server("cn=project,ou=servers", "project", null)).getId();
        otherServerId = servers.save(server("cn=other,ou=servers", "other", null)).getId();

        contactService.addUser(theirServerId, addedId, "alice");
    }

    @Test
    void aPointOfContactNamedByTheDirectoryManagesThatServer() {
        assertThat(policy.mayManageContacts(theirServerId, signedIn(publishedId, "published"))).isTrue();
        assertThat(policy.mayManageContacts(otherServerId, signedIn(publishedId, "published")))
                .as("and no other")
                .isFalse();
    }

    /** A contact added here counts the same as one the directory publishes. */
    @Test
    void soDoesSomebodyAddedHere() {
        assertThat(policy.mayManageContacts(theirServerId, signedIn(addedId, "added"))).isTrue();
    }

    @Test
    void whoeverRunsAProjectManagesEveryServerInIt() {
        var project = projectService.create("Payroll", null, "alice");
        projectService.addAdmin(project.getId(), strangerId);
        projectService.addServer(project.getId(), projectServerId);

        Authentication projectAdmin = signedIn(strangerId, "stranger");
        assertThat(policy.mayManageContacts(projectServerId, projectAdmin)).isTrue();
        assertThat(policy.mayManageContacts(otherServerId, projectAdmin))
                .as("a server the project does not hold")
                .isFalse();

        // Taking the server out of the project takes the management right with it.
        projectService.removeServer(project.getId(), projectServerId);
        assertThat(policy.mayManageContacts(projectServerId, projectAdmin)).isFalse();
    }

    /**
     * Being in a project is a grouping, not an authority. Handing everybody in it the
     * contact lists of every server in it would make it one nobody granted.
     */
    @Test
    void merelyBeingInTheProjectManagesNothing() {
        var project = projectService.create("Payroll", null, "alice");
        projectService.addMember(project.getId(), strangerId);
        projectService.addServer(project.getId(), projectServerId);

        Authentication member = signedIn(strangerId, "stranger");
        assertThat(policy.mayManageContacts(projectServerId, member)).isFalse();

        projectService.addAdmin(project.getId(), strangerId);
        assertThat(policy.mayManageContacts(projectServerId, member)).isTrue();

        // And giving the role back leaves them in the project with nothing extra.
        projectService.removeAdmin(project.getId(), strangerId);
        assertThat(policy.mayManageContacts(projectServerId, member)).isFalse();
    }

    @Test
    void somebodyWithNoConnectionToTheServerManagesNothing() {
        Authentication stranger = signedIn(strangerId, "stranger");
        assertThat(policy.mayManageContacts(theirServerId, stranger)).isFalse();
        assertThat(policy.mayManageContacts(projectServerId, stranger)).isFalse();
    }

    /** An administrator is not associated with anything and manages all of it. */
    @Test
    void anAdministratorManagesEverything() {
        Authentication admin = authentication(new DirectoryPrincipal(
                "root", "Root", null, null, DirectoryPrincipal.AuthenticationMethod.LDAP,
                List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))));

        assertThat(policy.mayManageContacts(theirServerId, admin)).isTrue();
        assertThat(policy.mayManageContacts(otherServerId, admin)).isTrue();
    }

    /** What the association is for: adding the colleague, without asking anybody. */
    @Test
    void aContactAddsAnotherContactToTheirOwnServer() throws Exception {
        mockMvc.perform(post(contacts(theirServerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"duty.desk@example.gov\"}")
                        .with(csrf())
                        .with(principal(publishedId, "published")))
                .andExpect(status().isCreated());

        assertThat(contacts.findByServerIdOrderByAddedAtAscIdAsc(theirServerId)).hasSize(2);
    }

    @Test
    void andIsRefusedOnAServerThatIsNotTheirs() throws Exception {
        mockMvc.perform(post(contacts(otherServerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"duty.desk@example.gov\"}")
                        .with(csrf())
                        .with(principal(publishedId, "published")))
                .andExpect(status().isForbidden())
                // A refusal that says why, rather than an unexplained 403 or a 500.
                .andExpect(jsonPath("$.title").value("Not allowed"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("point of contact")))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("administrator of a project")));

        assertThat(contacts.findByServerIdOrderByAddedAtAscIdAsc(otherServerId)).isEmpty();
    }

    @Test
    void removingIsTheSameQuestion() throws Exception {
        Long contactId = contacts.findByServerIdOrderByAddedAtAscIdAsc(theirServerId)
                .getFirst()
                .getId();

        mockMvc.perform(delete(contacts(theirServerId) + "/" + contactId)
                        .with(csrf())
                        .with(principal(strangerId, "stranger")))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete(contacts(theirServerId) + "/" + contactId)
                        .with(csrf())
                        .with(principal(publishedId, "published")))
                .andExpect(status().isNoContent());
    }

    /** The page asks the same question, so the controls appear only where they work. */
    @Test
    void theReadSaysWhetherThisPersonMayEdit() throws Exception {
        mockMvc.perform(get(contacts(theirServerId)).with(principal(publishedId, "published")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(true));

        mockMvc.perform(get(contacts(theirServerId)).with(principal(strangerId, "stranger")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(false))
                // They still see who the contacts are; it is the directory's data.
                .andExpect(jsonPath("$.directory[0]").value("published@example.gov"));
    }

    /**
     * Probing asks the same question, because it is the same relationship: the people with
     * something to do with this server. It opens a connection from here to there, so it is
     * not something to leave to anybody who can reach the page.
     */
    @Test
    void probingIsForThePeopleTheServerBelongsTo() throws Exception {
        // These entries say nowhere they live, so there is nothing to connect to - a
        // refusal about the request, not a fault. What is being tested is who may ask.
        mockMvc.perform(post(probe(theirServerId) + "?port=1")
                        .with(csrf())
                        .with(principal(publishedId, "published")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Nowhere to probe"));

        mockMvc.perform(post(probe(otherServerId) + "?port=1")
                        .with(csrf())
                        .with(principal(publishedId, "published")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(probe(theirServerId) + "?port=1")
                        .with(csrf())
                        .with(principal(strangerId, "stranger")))
                .andExpect(status().isForbidden());
    }

    private String probe(Long serverId) {
        return "/api/v1/servers/" + serverId + "/probe";
    }

    private String contacts(Long serverId) {
        return "/api/v1/servers/" + serverId + "/contacts";
    }

    private Authentication signedIn(Long userId, String username) {
        return authentication(new DirectoryPrincipal(
                username,
                username,
                userId,
                "uid=" + username + ",ou=people",
                DirectoryPrincipal.AuthenticationMethod.LDAP,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private Authentication authentication(DirectoryPrincipal principal) {
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    /** The real principal rather than a mock user: the rule is about a directory entry. */
    private RequestPostProcessor principal(Long userId, String username) {
        return SecurityMockMvcRequestPostProcessors.authentication(signedIn(userId, username));
    }

    private DirectoryUser user(String dn, String uid, String displayName) {
        DirectoryUser user = new DirectoryUser(dn);
        user.setUid(uid);
        user.setDisplayName(displayName);
        user.refreshIdentifiers(uid + "@example.gov");
        user.markSynced(Instant.now());
        return user;
    }

    private DirectoryServer server(String dn, String commonName, String poc) {
        DirectoryServer server = new DirectoryServer(dn);
        server.setCommonName(commonName);
        if (poc != null) {
            server.setServerPocs(List.of(poc));
        }
        server.markSynced(Instant.now());
        return server;
    }
}

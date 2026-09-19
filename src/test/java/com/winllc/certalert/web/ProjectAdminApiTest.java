package com.winllc.certalert.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.ProjectRepository;
import com.winllc.certalert.service.ProjectService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/**
 * Handing somebody the running of a project.
 *
 * <p>Run with project editing open to anyone signed in, which is what makes the point: even
 * where the deployment lets everybody curate projects, saying who runs one is an
 * administrator's, because it hands over the contact lists of every server in it and the
 * notices about their certificates.
 */
@SpringBootTest(properties = "cert-alert.security.contact-editors=AUTHENTICATED")
@ActiveProfiles("test")
class ProjectAdminApiTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private DirectoryUserRepository users;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private MockMvc mockMvc;
    private Long projectId;
    private Long aliceId;

    @BeforeEach
    void seed() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        projects.deleteAll();
        users.deleteAll();

        DirectoryUser alice = new DirectoryUser("uid=alice,ou=people");
        alice.setUid("alice");
        alice.setDisplayName("Alice Archer");
        alice.refreshIdentifiers("alice@example.gov");
        alice.markSynced(Instant.now());
        aliceId = users.save(alice).getId();

        projectId = projectService.create("Payroll migration", "Moving payroll", "root").getId();
    }

    @Test
    void anAdministratorHandsItOverAndTakesItBack() throws Exception {
        mockMvc.perform(post("/api/v1/projects/{id}/admins/{userId}", projectId, aliceId)
                        .with(admin())
                        .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(adminsOf()).extracting(DirectoryUser::getUid).containsExactly("alice");
        // And they are in the project, because there is no running it from outside.
        assertThat(membersOf()).extracting(DirectoryUser::getUid).containsExactly("alice");

        mockMvc.perform(delete("/api/v1/projects/{id}/admins/{userId}", projectId, aliceId)
                        .with(admin())
                        .with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(adminsOf()).isEmpty();
        assertThat(membersOf()).as("stepping down is not leaving").hasSize(1);
    }

    /** Everything else about a project is open here; this is not. */
    @Test
    void somebodyWhoMayEditTheProjectStillMayNotSayWhoRunsIt() throws Exception {
        mockMvc.perform(post("/api/v1/projects/{id}/users/{userId}", projectId, aliceId)
                        .with(reader())
                        .with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/projects/{id}/admins/{userId}", projectId, aliceId)
                        .with(reader())
                        .with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/projects/{id}/admins/{userId}", projectId, aliceId)
                        .with(reader())
                        .with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(adminsOf()).isEmpty();
    }

    @Test
    void theListSaysHowManyRunEachProject() throws Exception {
        projectService.addAdmin(projectId, aliceId);

        mockMvc.perform(get("/api/v1/projects").with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].members").value(1))
                .andExpect(jsonPath("$[0].admins").value(1));
    }

    /** The page marks whoever runs it, and offers the role to administrators. */
    @Test
    void theProjectPageMarksThemAndOffersTheRole() throws Exception {
        projectService.addAdmin(projectId, aliceId);

        mockMvc.perform(get("/projects/{id}", projectId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Step down")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("project administrator manages")));

        // A reader sees who runs it and is offered nothing.
        mockMvc.perform(get("/projects/{id}", projectId).with(reader()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Step down"))));
    }

    private List<DirectoryUser> adminsOf() {
        return transactionTemplate.execute(status -> List.copyOf(projectService.get(projectId).getAdmins()));
    }

    private List<DirectoryUser> membersOf() {
        return transactionTemplate.execute(status -> List.copyOf(projectService.get(projectId).getMembers()));
    }

    private RequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors.user("root").roles("USER", "ADMIN");
    }

    private RequestPostProcessor reader() {
        return SecurityMockMvcRequestPostProcessors.user("reader").roles("USER");
    }
}

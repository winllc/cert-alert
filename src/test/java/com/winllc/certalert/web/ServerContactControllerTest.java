package com.winllc.certalert.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.ServerContactRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Managing a server's points of contact, through the stack a browser goes through:
 * the security filter chain, the controller, the service, a real database.
 */
@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(roles = "ADMIN")
class ServerContactControllerTest {

    private static final String SERVERS = "/api/v1/datatables/servers";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private DirectoryUserRepository userRepository;

    @Autowired
    private DirectoryServerRepository serverRepository;

    @Autowired
    private ServerContactRepository contactRepository;

    private MockMvc mockMvc;
    private Long serverId;
    private Long aliceId;

    @BeforeEach
    void seed() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        contactRepository.deleteAll();
        serverRepository.deleteAll();
        userRepository.deleteAll();

        DirectoryUser alice = new DirectoryUser("uid=alice,ou=people");
        alice.setUid("alice");
        alice.setDisplayName("Alice Archer");
        alice.setCommonName("Alice Archer");
        alice.refreshIdentifiers("alice@example.gov");
        alice.markSynced(Instant.now());
        aliceId = userRepository.save(alice).getId();

        DirectoryServer server = new DirectoryServer("cn=web01,ou=servers");
        server.setCommonName("web01");
        server.setServerPocs(List.of("ops@example.gov"));
        server.markSynced(Instant.now());
        serverId = serverRepository.save(server).getId();
    }

    @Test
    void readsBothListsAndSaysWhetherTheManagedOneCanBeEdited() throws Exception {
        mockMvc.perform(get(contacts()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.directory.length()").value(1))
                .andExpect(jsonPath("$.directory[0]").value("ops@example.gov"))
                .andExpect(jsonPath("$.managed.length()").value(0))
                .andExpect(jsonPath("$.editable").value(true));
    }

    @Test
    void addsAPersonAsAContact() throws Exception {
        mockMvc.perform(post(contacts())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":%d}".formatted(aliceId))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Alice Archer"))
                .andExpect(jsonPath("$.address").value("alice@example.gov"))
                .andExpect(jsonPath("$.userId").value(aliceId))
                .andExpect(jsonPath("$.userDn").value("uid=alice,ou=people"))
                .andExpect(jsonPath("$.addedBy").value("user"));

        mockMvc.perform(get(contacts())).andExpect(jsonPath("$.managed.length()").value(1));
    }

    @Test
    void addsAnAddressThatBelongsToNobody() throws Exception {
        mockMvc.perform(post(contacts())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"Duty.Desk@example.gov\"}")
                        .with(csrf()))
                .andExpect(status().isCreated())
                // Lowercased on the way in, the same as every other value joined on.
                .andExpect(jsonPath("$.label").value("duty.desk@example.gov"))
                .andExpect(jsonPath("$.address").value("duty.desk@example.gov"))
                .andExpect(jsonPath("$.userId").doesNotExist());
    }

    /**
     * The two ways of naming a contact meet here: an address the directory knows becomes a
     * link to that person, so the filter that asks which servers they are responsible for
     * finds this one whichever way it was added.
     */
    @Test
    void linksAnAddressToThePersonItBelongsTo() throws Exception {
        mockMvc.perform(post(contacts())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.gov\"}")
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Alice Archer"))
                .andExpect(jsonPath("$.userId").value(aliceId));
    }

    @Test
    void refusesTheSamePersonTwiceHoweverTheyAreNamed() throws Exception {
        addAlice();

        mockMvc.perform(post(contacts())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":%d}".formatted(aliceId))
                        .with(csrf()))
                .andExpect(status().isConflict());

        mockMvc.perform(post(contacts())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alice@example.gov\"}")
                        .with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void refusesSomethingThatIsNotAnAddress() throws Exception {
        mockMvc.perform(post(contacts())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"the ops team\"}")
                        .with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void refusesARequestNamingBothOrNeither() throws Exception {
        mockMvc.perform(post(contacts())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":%d,\"email\":\"alice@example.gov\"}".formatted(aliceId))
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post(contacts()).contentType(MediaType.APPLICATION_JSON).content("{}").with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void removesAContact() throws Exception {
        long contactId = addAlice();

        mockMvc.perform(delete(contacts() + "/" + contactId).with(csrf())).andExpect(status().isNoContent());

        mockMvc.perform(get(contacts())).andExpect(jsonPath("$.managed.length()").value(0));
        assertThat(contactRepository.count()).isZero();
    }

    @Test
    void willNotRemoveAContactBelongingToAnotherServer() throws Exception {
        long contactId = addAlice();

        DirectoryServer other = new DirectoryServer("cn=web02,ou=servers");
        other.setCommonName("web02");
        other.markSynced(Instant.now());
        Long otherId = serverRepository.save(other).getId();

        mockMvc.perform(delete("/api/v1/servers/" + otherId + "/contacts/" + contactId).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(contactRepository.count()).isEqualTo(1);
    }

    /** A contact added here is a point of contact for every purpose, the search table included. */
    @Test
    void theServersTableFindsAServerByAContactAddedHere() throws Exception {
        addAlice();

        mockMvc.perform(post(SERVERS + "?pocUserId=" + aliceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data[0].commonName").value("web01"))
                .andExpect(jsonPath("$.data[0].managedContactCount").value(1));

        mockMvc.perform(post(SERVERS + "?poc=alice@example.gov")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1));
    }

    /**
     * A server named by the directory <em>and</em> by a contact added here is still one
     * server. Matching through a join rather than an exists would return it twice and count
     * it twice.
     */
    @Test
    void aServerContactedBothWaysIsReturnedOnce() throws Exception {
        // Fetched with its contacts: replacing the set compares against the current one.
        DirectoryServer server = serverRepository.findWithPocsById(serverId).orElseThrow();
        server.setServerPocs(List.of("alice@example.gov", "ops@example.gov"));
        serverRepository.save(server);
        addAlice();

        mockMvc.perform(post(SERVERS + "?pocUserId=" + aliceId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(serversRequest())
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordsFiltered").value(1))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void findsPeopleForThePicker() throws Exception {
        mockMvc.perform(get("/api/v1/users/search").param("q", "arch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].displayName").value("Alice Archer"))
                .andExpect(jsonPath("$[0].email").value("alice@example.gov"));

        mockMvc.perform(get("/api/v1/users/search").param("q", "nobody"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aReaderSeesTheContactsButIsNotOfferedTheControls() throws Exception {
        mockMvc.perform(get(contacts()).with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(false));
    }

    @Test
    void aReaderCannotChangeTheContacts() throws Exception {
        long contactId = addAlice();

        mockMvc.perform(post(contacts())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"someone@example.gov\"}")
                        .with(csrf())
                        .with(reader()))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete(contacts() + "/" + contactId).with(csrf()).with(reader()))
                .andExpect(status().isForbidden());

        assertThat(contactRepository.count()).isEqualTo(1);
    }

    @Test
    void aWriteWithoutACsrfTokenIsRefused() throws Exception {
        mockMvc.perform(post(contacts())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"someone@example.gov\"}"))
                .andExpect(status().isForbidden());
    }

    /** Somebody signed in who is not an administrator. */
    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor reader() {
        return user("reader").roles("USER");
    }

    private String contacts() {
        return "/api/v1/servers/" + serverId + "/contacts";
    }

    private long addAlice() throws Exception {
        String body = mockMvc.perform(post(contacts())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":%d}".formatted(aliceId))
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode node = JSON.readTree(body);
        return node.get("id").asLong();
    }

    /** The smallest DataTables request the servers table would send. */
    private String serversRequest() {
        return """
                {"draw":1,"start":0,"length":10,
                 "columns":[{"data":"commonName","name":"commonName","searchable":true,"orderable":true,
                             "search":{"value":"","regex":false}}],
                 "order":[{"column":0,"dir":"asc"}],
                 "search":{"value":"","regex":false}}""";
    }
}

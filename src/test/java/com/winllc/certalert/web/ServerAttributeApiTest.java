package com.winllc.certalert.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.domain.ServerAttributeType;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.ServerAttributeDefinitionRepository;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.service.ServerAttributeService;
import com.winllc.certalert.support.EmbeddedDirectory;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Defining managed attributes, and filling them in on a server.
 *
 * <p>Naming one adds a field to every server at once, so that is an administrator's; so is
 * writing to the directory. What a server's entry holds is shown to anybody looking at that
 * server, and the read says whether they may change it.
 */
@SpringBootTest
@ActiveProfiles("test")
class ServerAttributeApiTest {

    private static final String ADMIN = "/api/v1/admin/server-attributes";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ServerAttributeService attributes;

    @Autowired
    private ServerAttributeDefinitionRepository definitions;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private DirectoryServerRepository servers;

    private static EmbeddedDirectory directory;

    /** The directory outlives each test, so the entry is added once and reset per test. */
    private static String serverDn;

    private MockMvc mockMvc;
    private Long serverId;

    @BeforeAll
    static void startDirectory() {
        directory = new EmbeddedDirectory();
        serverDn = directory.addServer(
                "api-web01", "https://api-web01.example.gov", new String[] {"ops@example.gov"});
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
    void seed() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        definitions.deleteAll();
        servers.deleteAll();

        directory.modify(serverDn, "ATOStatus");
        directory.modify(serverDn, "icNetworks");
        syncService.syncServers();
        serverId = servers.findByDn(serverDn).orElseThrow().getId();
    }

    @Test
    void anAdministratorMakesAnAttributeEditableAndItAppearsOnEveryServer() throws Exception {
        mockMvc.perform(post(ADMIN)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ldapAttribute":"ATOStatus","name":"ATO status","description":"Where it stands",
                                 "type":"CHOICE","multiValued":false,"options":["Authorized","Denied"]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ldapAttribute").value("ATOStatus"))
                .andExpect(jsonPath("$.name").value("ATO status"))
                .andExpect(jsonPath("$.typeLabel").value("Drop-down"))
                .andExpect(jsonPath("$.options[1]").value("Denied"));

        mockMvc.perform(get("/api/v1/servers/{id}/attributes", serverId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes.length()").value(1))
                .andExpect(jsonPath("$.attributes[0].values.length()").value(0))
                .andExpect(jsonPath("$.editable").value(true));
    }

    @Test
    void theThreeKindsAreAllDefinable() throws Exception {
        define("ATOStatus", "ATO status", "CHOICE", false, "[\"Authorized\",\"Denied\"]");
        define("icNetworks", "Networks", "TEXT", true, "[]");
        define("icAudited", "In scope for audit", "BOOLEAN", false, "[]");

        mockMvc.perform(get(ADMIN).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[1].multiValued").value(true))
                .andExpect(jsonPath("$[2].type").value("BOOLEAN"))
                .andExpect(jsonPath("$[2].multiValued").value(false));
    }

    @Test
    void aReaderNeitherDefinesNorSets() throws Exception {
        Long id = attributes
                .create("ATOStatus", "ATO status", null, ServerAttributeType.TEXT, false, null, null, "alice")
                .getId();

        mockMvc.perform(get(ADMIN).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(post(ADMIN)
                        .with(reader())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ldapAttribute\":\"sneaky\",\"type\":\"TEXT\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(ADMIN + "/" + id).with(reader()).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/servers/{id}/attributes/{definitionId}", serverId, id)
                        .with(reader())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":[\"Authorized\"]}"))
                .andExpect(status().isForbidden());

        // But they see what a server holds, and are told they may not change it.
        mockMvc.perform(get("/api/v1/servers/{id}/attributes", serverId).with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(false))
                .andExpect(jsonPath("$.attributes[0].name").value("ATO status"))
                .andExpect(jsonPath("$.attributes[0].ldapAttribute").value("ATOStatus"));
    }

    @Test
    void nobodySignedInSeesAnyOfIt() throws Exception {
        mockMvc.perform(get(ADMIN).with(anonymous())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/servers/{id}/attributes", serverId).with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void settingAValueWritesItToTheDirectoryAndClearingItTakesItOff() throws Exception {
        Long id = attributes
                .create("icNetworks", "Networks", null, ServerAttributeType.TEXT, true, null, null, "alice")
                .getId();

        mockMvc.perform(put("/api/v1/servers/{id}/attributes/{definitionId}", serverId, id)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":[\"JWICS\",\"SIPRNET\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values[0]").value("JWICS"))
                .andExpect(jsonPath("$.values[1]").value("SIPRNET"));
        assertThat(directory.valuesOf(serverDn, "icNetworks")).containsExactlyInAnyOrder("JWICS", "SIPRNET");

        mockMvc.perform(put("/api/v1/servers/{id}/attributes/{definitionId}", serverId, id)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.length()").value(0));
        assertThat(directory.valuesOf(serverDn, "icNetworks")).isEmpty();
    }

    /** The refusal says what the rule is, because the page prints it as it stands. */
    @Test
    void aValueThatBreaksTheDefinitionIsRefusedInWords() throws Exception {
        Long id = attributes
                .create("ATOStatus", "ATO status", null, ServerAttributeType.CHOICE, false, List.of("Authorized"),
                        null, "alice")
                .getId();

        mockMvc.perform(put("/api/v1/servers/{id}/attributes/{definitionId}", serverId, id)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":[\"Sandbox\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("not one of the values")));

        mockMvc.perform(post(ADMIN)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ldapAttribute\":\"icNowhere\",\"type\":\"CHOICE\",\"options\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("at least one value")));
    }

    /** Taking it off the list stops it being offered; the directory keeps what it holds. */
    @Test
    void takingAnAttributeOffTheListLeavesTheDirectoryAlone() throws Exception {
        Long id = attributes
                .create("icNetworks", "Networks", null, ServerAttributeType.TEXT, true, null, null, "alice")
                .getId();
        attributes.setValues(serverId, id, List.of("JWICS"), "alice");

        mockMvc.perform(delete(ADMIN + "/" + id).with(admin()).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/servers/{id}/attributes", serverId).with(admin()))
                .andExpect(jsonPath("$.attributes.length()").value(0));
        assertThat(directory.valuesOf(serverDn, "icNetworks")).containsExactly("JWICS");
    }

    /** The page renders the card, which fills itself in from the endpoint. */
    @Test
    void theServersPageCarriesTheAttributeCard() throws Exception {
        mockMvc.perform(get("/servers/{id}", serverId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"server-attributes\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Directory attributes")));
    }

    @Test
    void theAdministrationPageCarriesTheEditor() throws Exception {
        mockMvc.perform(get("/admin").with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Editable directory attributes")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"attribute-form\"")));
    }

    /**
     * The refusal has to name what is already there. This one used to come back titled
     * "Already a point of contact", because one exception served four unrelated kinds of
     * duplicate - which is a refusal that sends whoever reads it to the wrong page.
     */
    @Test
    void definingTheSameAttributeTwiceIsRefusedInItsOwnWords() throws Exception {
        define("ATOStatus", "ATO status", "TEXT", false, "null");

        mockMvc.perform(post(ADMIN)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ldapAttribute":"atostatus","name":"Something else","type":"TEXT",
                                 "multiValued":false,"options":null}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Attribute already managed"))
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("already managed here")));
    }

    private void define(String attribute, String name, String type, boolean multiValued, String options)
            throws Exception {
        mockMvc.perform(post(ADMIN)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ldapAttribute":"%s","name":"%s","type":"%s","multiValued":%s,"options":%s}"""
                                .formatted(attribute, name, type, multiValued, options)))
                .andExpect(status().isCreated());
    }

    private RequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors.user("root").roles("USER", "ADMIN");
    }

    private RequestPostProcessor reader() {
        return SecurityMockMvcRequestPostProcessors.user("reader").roles("USER");
    }
}

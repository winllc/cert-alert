package com.winllc.certalert.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.ServerAttributeType;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.ServerAttributeDefinitionRepository;
import com.winllc.certalert.repository.ServerAttributeValueRepository;
import com.winllc.certalert.service.ServerAttributeService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Defining managed attributes, and filling them in on a server.
 *
 * <p>Defining one adds a field to every server at once and retiring one takes every value
 * with it, so both are an administrator's. What a server holds is shown to anybody looking
 * at that server, and the read says whether they may change it.
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
    private ServerAttributeValueRepository values;

    @Autowired
    private DirectoryServerRepository servers;

    private MockMvc mockMvc;
    private Long serverId;

    @BeforeEach
    void seed() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        values.deleteAll();
        definitions.deleteAll();
        servers.deleteAll();

        DirectoryServer web01 = new DirectoryServer("cn=web01,ou=servers");
        web01.setCommonName("web01");
        web01.markSynced(Instant.now());
        serverId = servers.save(web01).getId();
    }

    @Test
    void anAdministratorDefinesAnAttributeAndItAppearsOnEveryServer() throws Exception {
        mockMvc.perform(post(ADMIN)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Environment","description":"Where it runs","type":"CHOICE",
                                 "multiValued":false,"options":["Production","Staging"]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Environment"))
                .andExpect(jsonPath("$.typeLabel").value("Drop-down"))
                .andExpect(jsonPath("$.options[1]").value("Staging"));

        mockMvc.perform(get("/api/v1/servers/{id}/attributes", serverId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes.length()").value(1))
                .andExpect(jsonPath("$.attributes[0].values.length()").value(0))
                .andExpect(jsonPath("$.editable").value(true));
    }

    @Test
    void theThreeKindsAreAllDefinable() throws Exception {
        define("Environment", "CHOICE", false, "[\"Production\",\"Staging\"]");
        define("Tags", "TEXT", true, "[]");
        define("In scope for audit", "BOOLEAN", false, "[]");

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
                .create("Environment", null, ServerAttributeType.TEXT, false, null, null, "alice")
                .getId();

        mockMvc.perform(get(ADMIN).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(post(ADMIN)
                        .with(reader())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Sneaky\",\"type\":\"TEXT\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(ADMIN + "/" + id).with(reader()).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/servers/{id}/attributes/{definitionId}", serverId, id)
                        .with(reader())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":[\"Production\"]}"))
                .andExpect(status().isForbidden());

        // But they see what a server holds, and are told they may not change it.
        mockMvc.perform(get("/api/v1/servers/{id}/attributes", serverId).with(reader()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editable").value(false))
                .andExpect(jsonPath("$.attributes[0].name").value("Environment"));
    }

    @Test
    void nobodySignedInSeesAnyOfIt() throws Exception {
        mockMvc.perform(get(ADMIN).with(anonymous())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/servers/{id}/attributes", serverId).with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void settingAValueAndClearingItAgain() throws Exception {
        Long id = attributes
                .create("Tags", null, ServerAttributeType.TEXT, true, null, null, "alice")
                .getId();

        mockMvc.perform(put("/api/v1/servers/{id}/attributes/{definitionId}", serverId, id)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":[\"payroll\",\"tier-1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values[0]").value("payroll"))
                .andExpect(jsonPath("$.values[1]").value("tier-1"));

        mockMvc.perform(put("/api/v1/servers/{id}/attributes/{definitionId}", serverId, id)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"values\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.length()").value(0));
    }

    /** The refusal says what the rule is, because the page prints it as it stands. */
    @Test
    void aValueThatBreaksTheDefinitionIsRefusedInWords() throws Exception {
        Long id = attributes
                .create("Environment", null, ServerAttributeType.CHOICE, false, List.of("Production"), null, "alice")
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
                        .content("{\"name\":\"Nowhere\",\"type\":\"CHOICE\",\"options\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("at least one value")));
    }

    @Test
    void retiringAnAttributeTakesItOffEveryServer() throws Exception {
        Long id = attributes
                .create("Tags", null, ServerAttributeType.TEXT, true, null, null, "alice")
                .getId();
        attributes.setValues(serverId, id, List.of("payroll"), "alice");

        mockMvc.perform(get(ADMIN).with(admin()))
                .andExpect(jsonPath("$[0].serversHolding").value(1));

        mockMvc.perform(delete(ADMIN + "/" + id).with(admin()).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/servers/{id}/attributes", serverId).with(admin()))
                .andExpect(jsonPath("$.attributes.length()").value(0));
    }

    /** The page renders the card, which fills itself in from the endpoint. */
    @Test
    void theServersPageCarriesTheAttributeCard() throws Exception {
        mockMvc.perform(get("/servers/{id}", serverId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"server-attributes\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Managed attributes")));
    }

    @Test
    void theAdministrationPageCarriesTheEditor() throws Exception {
        mockMvc.perform(get("/admin").with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Server attributes")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"attribute-form\"")));
    }

    private void define(String name, String type, boolean multiValued, String options) throws Exception {
        mockMvc.perform(post(ADMIN)
                        .with(admin())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","type":"%s","multiValued":%s,"options":%s}"""
                                .formatted(name, type, multiValued, options)))
                .andExpect(status().isCreated());
    }

    private RequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors.user("root").roles("USER", "ADMIN");
    }

    private RequestPostProcessor reader() {
        return SecurityMockMvcRequestPostProcessors.user("reader").roles("USER");
    }
}

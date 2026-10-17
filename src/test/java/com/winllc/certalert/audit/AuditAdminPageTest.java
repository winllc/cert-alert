package com.winllc.certalert.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Arrays;
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
import org.springframework.web.context.WebApplicationContext;

/** The audit log page: filters and paging, the detail view, CSV export, verification, and who may see it. */
@SpringBootTest
@ActiveProfiles("test")
class AuditAdminPageTest {

    private static final String EVENTS = "/api/v1/admin/audit/events";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AuditLog audit;

    @Autowired
    private AuditStore store;

    private MockMvc mockMvc;
    private String run;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        run = "run" + System.nanoTime();
        for (int i = 0; i < 30; i++) {
            audit.record(AuditRecord.of(AuditRecord.Category.DATA, "test.paging")
                    .actor(new AuditRecord.Actor(AuditRecord.ActorType.USER, i % 2 == 0 ? "even-" + run : "odd-" + run,
                            null, List.of("USER")))
                    .source(new AuditRecord.Source("10.9.8." + (i % 3), "test"))
                    .target("project", "p" + i, "Project " + i)
                    .detail("run", run)
                    .detail("n", i));
        }
        audit.record(AuditRecord.of(AuditRecord.Category.AUTHENTICATION, "auth.login")
                .outcome(AuditRecord.Outcome.FAILURE)
                .actor(AuditRecord.Actor.anonymous("mallory-" + run))
                .detail("run", run));
    }

    private static RequestPostProcessor admin() {
        return SecurityMockMvcRequestPostProcessors.user("root").roles("USER", "ADMIN");
    }

    private static RequestPostProcessor reader() {
        return SecurityMockMvcRequestPostProcessors.user("reader").roles("USER");
    }

    @Test
    void thePageRendersWithItsFilters() throws Exception {
        mockMvc.perform(get("/admin/audit").with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"audit-log\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"authentication\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"access.denied\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Verify integrity")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Kept forever")));
    }

    @Test
    void filtersAndPagesNewestFirst() throws Exception {
        mockMvc.perform(get(EVENTS).with(admin()).param("q", run).param("action", "test.paging")
                        .param("size", "10").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(30))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.content.length()").value(10))
                .andExpect(jsonPath("$.content[0].targetName").value("Project 29"))
                .andExpect(jsonPath("$.content[9].targetName").value("Project 20"))
                .andExpect(jsonPath("$.content[0].category").value("data"))
                .andExpect(jsonPath("$.content[0].severity").value("info"));
        mockMvc.perform(get(EVENTS).with(admin()).param("q", run).param("action", "test.paging")
                        .param("size", "10").param("page", "2"))
                .andExpect(jsonPath("$.content[9].targetName").value("Project 0"))
                .andExpect(jsonPath("$.last").value(true));

        // Actor and target contain, source address, category, outcome, severity.
        mockMvc.perform(get(EVENTS).with(admin()).param("actor", "even-" + run))
                .andExpect(jsonPath("$.totalElements").value(15));
        mockMvc.perform(get(EVENTS).with(admin()).param("q", run).param("target", "p2"))
                .andExpect(jsonPath("$.totalElements").value(11));
        mockMvc.perform(get(EVENTS).with(admin()).param("q", run).param("sourceIp", "10.9.8.1"))
                .andExpect(jsonPath("$.totalElements").value(10));
        mockMvc.perform(get(EVENTS).with(admin()).param("q", run).param("category", "authentication")
                        .param("outcome", "failure").param("severity", "warning"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].actorId").value("mallory-" + run));
        String today = Instant.now().toString().substring(0, 10);
        mockMvc.perform(get(EVENTS).with(admin()).param("q", run).param("from", today).param("to", today))
                .andExpect(jsonPath("$.totalElements").value(31));
        mockMvc.perform(get(EVENTS).with(admin()).param("q", run).param("to", "2000-01-01"))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void oneEventOpensInFull() throws Exception {
        long seq = store.headSeq();
        mockMvc.perform(get(EVENTS + "/{seq}", seq).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seq").value(seq))
                .andExpect(jsonPath("$.hash").isString())
                .andExpect(jsonPath("$.event.action").value("auth.login"))
                .andExpect(jsonPath("$.event.actor.type").value("anonymous"))
                .andExpect(jsonPath("$.formatted").value(org.hamcrest.Matchers.containsString("\n")));
    }

    @Test
    void theCurrentFilterExportsAsCsv() throws Exception {
        String csv = mockMvc.perform(get("/api/v1/admin/audit/export").with(admin())
                        .param("format", "csv").param("q", run).param("action", "test.paging"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString();
        List<String> rows = Arrays.asList(csv.split("\r\n"));
        assertThat(rows).hasSize(31);
        assertThat(rows.getFirst()).isEqualTo(String.join(",", AuditAdminController.CSV_COLUMNS));
        assertThat(rows.get(1)).contains(",data,test.paging,success,info,user,odd-" + run + ",", ",10.9.8.2,project,p29,Project 29,");

        String ndjson = mockMvc.perform(get("/api/v1/admin/audit/export").with(admin())
                        .param("format", "ndjson").param("q", run))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // The thirty-one, and the CSV export just now, whose filter names this run.
        assertThat(ndjson.split("\n")).hasSize(32).allSatisfy(line -> assertThat(line).startsWith("{\"schema\":\"ens-audit/1\""));
    }

    @Test
    void verifyingIsAuditedWithItsOutcome() throws Exception {
        long head = store.headSeq();
        mockMvc.perform(post("/api/v1/admin/audit/verify").with(admin()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true))
                .andExpect(jsonPath("$.summary").value(org.hamcrest.Matchers.startsWith("Intact")));
        assertThat(store.after(head, 10)).extracting(StoredEvent::action).containsExactly("audit.verified");
        assertThat(store.after(head, 10).getFirst().line()).contains("\"intact\":true", "\"outcome\":\"success\"");
    }

    @Test
    void theDeliveryPanelListsEveryOutput() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit/delivery").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outputs[*].output").value(
                        org.hamcrest.Matchers.contains("stdout", "file", "splunk", "syslog")))
                .andExpect(jsonPath("$.outputs[0].enabled").value(true))
                .andExpect(jsonPath("$.retentionDays").value(0));
    }

    @Test
    void nonAdministratorsAreRefused() throws Exception {
        mockMvc.perform(get("/admin/audit").with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get(EVENTS).with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/audit/export").with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/audit/verify").with(reader()).with(csrf())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/audit/tokens").with(reader())).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/audit").with(anonymous())).andExpect(status().is3xxRedirection());
        mockMvc.perform(get(EVENTS).with(anonymous())).andExpect(status().isUnauthorized());
    }
}

package com.winllc.certalert.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.AuditEvent;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.repository.AuditEventRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The paging contract the history view relies on. */
@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(roles = "USER")
class AuditControllerTest {

    private static final int EVENTS = 25;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AuditEventRepository auditRepository;

    @Autowired
    private DirectoryUserRepository userRepository;

    private MockMvc mockMvc;
    private Long userId;

    @BeforeEach
    void seed() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        auditRepository.deleteAll();
        userRepository.deleteAll();

        DirectoryUser user = new DirectoryUser("uid=audited,ou=people");
        user.setUid("audited");
        user.setDisplayName("Audited Person");
        user.refreshIdentifiers("audited@example.gov");
        user.markSynced(Instant.now());
        userId = userRepository.save(user).getId();

        Instant start = Instant.now().minus(EVENTS, ChronoUnit.HOURS);
        List<AuditEvent> events = new ArrayList<>();
        for (int i = 0; i < EVENTS; i++) {
            events.add(AuditEvent.about(
                            AuditEvent.SubjectRef.of(user),
                            AuditAction.CERTIFICATE_STATUS_CHANGED,
                            "Change number " + i,
                            start.plus(i, ChronoUnit.HOURS))
                    .by("sync"));
        }
        auditRepository.saveAll(events);
    }

    @Test
    void returnsTheNewestRecordsFirst() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}/audit", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(10))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(EVENTS))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false))
                .andExpect(jsonPath("$.content[0].summary").value("Change number 24"))
                .andExpect(jsonPath("$.content[0].label").value("Certificate status changed"))
                .andExpect(jsonPath("$.content[0].actor").value("sync"));
    }

    @Test
    void walksThroughThePages() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}/audit", userId).param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].summary").value("Change number 14"))
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(false));

        mockMvc.perform(get("/api/v1/users/{id}/audit", userId).param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5))
                .andExpect(jsonPath("$.content[0].summary").value("Change number 4"))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void aPageBeyondTheEndIsEmptyRatherThanAnError() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}/audit", userId).param("page", "9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(EVENTS));
    }

    /** A page size somebody made up is bounded rather than honoured. */
    @Test
    void boundsThePageSize() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}/audit", userId).param("size", "5000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));

        mockMvc.perform(get("/api/v1/users/{id}/audit", userId).param("size", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1));
    }

    @Test
    void anEntryWithNoHistoryHasAnEmptyOne() throws Exception {
        DirectoryUser other = new DirectoryUser("uid=quiet,ou=people");
        other.setUid("quiet");
        other.markSynced(Instant.now());
        Long otherId = userRepository.save(other).getId();

        mockMvc.perform(get("/api/v1/users/{id}/audit", otherId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void asksAboutSomethingThatDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}/audit", 999_999)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/servers/{id}/audit", 999_999)).andExpect(status().isNotFound());
    }

    @Test
    void needsSomebodySignedIn() throws Exception {
        mockMvc.perform(get("/api/v1/users/{id}/audit", userId).with(anonymous()))
                .andExpect(status().isUnauthorized());
    }
}

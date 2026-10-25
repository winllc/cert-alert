package com.winllc.certalert.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.audit.AuditStore;
import com.winllc.certalert.audit.StoredEvent;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** A log level changed at run time is in the audit trail: who, which logger, from what to what (CA-20). */
@SpringBootTest
@ActiveProfiles("test")
class AuditedLoggersTest {

    private static final String LOGGER = "com.winllc.certalert.demo";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AuditStore audit;

    @Autowired
    private LoggingSystem loggingSystem;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @AfterEach
    void reset() {
        loggingSystem.setLogLevel(LOGGER, null);
    }

    @Test
    void changingALogLevelIsAudited() throws Exception {
        long head = audit.headSeq();
        mockMvc.perform(post("/actuator/loggers/" + LOGGER)
                        .with(user("root").roles("USER", "ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configuredLevel\":\"OFF\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/actuator/loggers/" + LOGGER).with(user("root").roles("USER", "ADMIN")))
                .andExpect(jsonPath("$.configuredLevel").value(LogLevel.OFF.name()));

        List<StoredEvent> changed = audit.after(head, 100).stream()
                .filter(event -> event.action().equals(AuditedLoggers.ACTION))
                .toList();
        assertThat(changed).singleElement().satisfies(event -> assertThat(event.line())
                .contains("\"root\"", LOGGER, "\"OFF\"", "\"category\":\"configuration\""));
    }

    @Test
    void anAuditorCannotChangeOne() throws Exception {
        long head = audit.headSeq();
        mockMvc.perform(post("/actuator/loggers/" + LOGGER)
                        .with(user("aud").roles("USER", "AUDITOR")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configuredLevel\":\"OFF\"}"))
                .andExpect(status().isForbidden());
        assertThat(audit.after(head, 100)).noneMatch(event -> event.action().equals(AuditedLoggers.ACTION));
    }
}

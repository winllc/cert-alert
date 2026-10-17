package com.winllc.certalert.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.winllc.certalert.audit.JobAudit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
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

/**
 * The ENS counters count what they say, and with no management port Prometheus is not
 * served at all - not even to an administrator.
 */
@SpringBootTest(properties = "management.defaults.metrics.export.enabled=true")
@ActiveProfiles("test")
class MetricsCountingTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private JobAudit jobs;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    void aFailedSignInIsCountedByMethodAndOutcome() throws Exception {
        double before = count("ens.auth.attempts", "method", "ldap", "outcome", "failure");

        // No directory answers here, so the password cannot be checked: a failure.
        mockMvc.perform(formLogin().user("nobody").password("wrong"));

        assertThat(count("ens.auth.attempts", "method", "ldap", "outcome", "failure")).isEqualTo(before + 1);
    }

    @Test
    void auditEventsAreCountedAsTheyCommit() {
        double before = count("ens.audit.events", "category", "system", "outcome", "success");

        jobs.run("counted job", () -> Map.of("done", 1), result -> result);

        assertThat(count("ens.audit.events", "category", "system", "outcome", "success")).isEqualTo(before + 1);
        Gauge lastSuccess = meters.find("ens.certalert.job.last.success.seconds").tag("job", "counted job").gauge();
        assertThat(lastSuccess).isNotNull();
        assertThat(lastSuccess.value()).isCloseTo(Instant.now().getEpochSecond(), org.assertj.core.data.Offset.offset(60.0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void withoutAManagementPortPrometheusIsNotServed() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    private double count(String name, String... tags) {
        Counter counter = meters.find(name).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }
}

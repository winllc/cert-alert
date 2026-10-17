package com.winllc.certalert.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.audit.JobAudit;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Metrics and health as the ENS baseline has them: Prometheus on the management port only,
 * open there and served nowhere else; liveness and readiness; and the business metrics under
 * their ENS names.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "MANAGEMENT_SERVER_PORT=0",
            "management.server.port=0",
            // Spring Boot's tests switch metric export off unless asked.
            "management.defaults.metrics.export.enabled=true",
            "management.prometheus.metrics.export.enabled=true"
        })
@ActiveProfiles("test")
class ObservabilityTest {

    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private JobAudit jobs;

    @Test
    void prometheusIsOpenOnTheManagementPortWithTheEnsMetrics() throws Exception {
        jobs.run("test job", () -> Map.of("done", 1), result -> result);

        HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus");

        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body())
                .contains("ens_license_mode{mode=\"FULL\"} 1.0")
                .contains("ens_license_mode{mode=\"UNLICENSED\"} 0.0")
                .contains("ens_license_days_remaining")
                .contains("ens_certalert_certificates{status=\"valid\"}")
                .contains("ens_certalert_certificates{status=\"expired\"}")
                .contains("ens_certalert_revocation_unknown")
                .contains("ens_certalert_mail_outbox{state=\"pending\"}")
                .contains("ens_certalert_job_last_success_seconds{job=\"test job\"}")
                .contains("ens_audit_events_total{category=\"system\",outcome=\"success\"}");
    }

    @Test
    void itIsServedNowhereElse() throws Exception {
        assertThat(get(port, "/actuator/prometheus").statusCode()).isIn(401, 302, 404);
        // The application's port has no actuator at all once it has a port of its own...
        assertThat(get(port, "/actuator/health").statusCode()).isEqualTo(404);
        // ...but keeps the probe paths.
        assertThat(get(port, "/livez").statusCode()).isEqualTo(200);
        assertThat(get(port, "/readyz").statusCode()).isEqualTo(200);
    }

    @Test
    void healthIsOpenThereAndTheRestIsNot() throws Exception {
        HttpResponse<String> liveness = get(managementPort, "/actuator/health/liveness");
        HttpResponse<String> readiness = get(managementPort, "/actuator/health/readiness");

        assertThat(liveness.statusCode()).isEqualTo(200);
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"status\":\"UP\"");
        // Details only to somebody authorised.
        assertThat(readiness.body()).doesNotContain("auditLog");

        assertThat(get(managementPort, "/actuator/loggers").statusCode()).isIn(401, 302, 403);
    }

    private HttpResponse<String> get(int on, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + on + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}

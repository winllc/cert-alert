package com.winllc.certalert.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.audit.JobAudit;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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

    @Autowired
    private EnsMetrics metrics;

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

    /**
     * The Helm chart's PrometheusRule alerts on these metrics by name, and on jobs by the
     * name they are run under. A rename on either side would leave an alert that can never
     * fire, which looks exactly like one with nothing to say.
     */
    @Test
    void everyMetricAndJobTheChartsAlertsNameExists() throws Exception {
        Path chart = Path.of("deploy/helm/cert-alert");
        String rule = Files.readString(chart.resolve("templates/prometheusrule.yaml"));
        Set<String> names = new LinkedHashSet<>();
        Matcher metric = Pattern.compile("\\b(ens_[a-z_]+|process_[a-z_]+)\\b").matcher(rule);
        while (metric.find()) {
            names.add(metric.group(1));
        }
        assertThat(names).contains(
                "ens_certalert_job_last_success_seconds", "ens_certalert_digest_truncated_total",
                "ens_certalert_revocation_unknown", "ens_certalert_certificates", "process_start_time_seconds");
        assertThat(rule).contains("status=\\\"expiring_soon\\\"");

        // The counter appears once something has counted.
        metrics.digestTruncated();
        jobs.run("test job", () -> Map.of("done", 1), result -> result);
        String scrape = get(managementPort, "/actuator/prometheus").body();
        for (String name : names) {
            assertThat(scrape).as("served: %s", name).containsPattern("(?m)^" + name + "[{ ]");
        }
        assertThat(scrape).contains("ens_certalert_certificates{status=\"expiring_soon\"}");

        String values = Files.readString(chart.resolve("values.yaml"));
        String sources;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            StringBuilder all = new StringBuilder();
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                all.append(Files.readString(file));
            }
            sources = all.toString();
        }
        Matcher job = Pattern.compile("(?m)^\\s*- job: (.+)$").matcher(values);
        int found = 0;
        while (job.find()) {
            found++;
            assertThat(sources).as("a job run as \"%s\"", job.group(1).trim()).contains("\"" + job.group(1).trim() + "\"");
        }
        assertThat(found).isPositive();
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

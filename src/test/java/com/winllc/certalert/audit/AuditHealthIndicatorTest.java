package com.winllc.certalert.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/** Readiness says DOWN when the audit file cannot be written, since a sign-in then cannot be audited. */
class AuditHealthIndicatorTest {

    @TempDir
    Path directory;

    private AuditSinks sinks;

    @AfterEach
    void detach() {
        if (sinks != null) {
            sinks.stop();
        }
    }

    @Test
    void withNoFileItIsUp() {
        EnsAuditProperties properties = new EnsAuditProperties();
        assertThat(health(properties).getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aWritableFileIsUp() {
        EnsAuditProperties properties = new EnsAuditProperties();
        properties.setFile(directory.resolve("audit.jsonl").toString());
        assertThat(health(properties).getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aFileThatCannotBeOpenedIsDown() throws Exception {
        java.nio.file.Files.writeString(directory.resolve("not-a-directory"), "x");
        EnsAuditProperties properties = new EnsAuditProperties();
        properties.setFile(directory.resolve("not-a-directory").resolve("audit.jsonl").toString());

        Health health = health(properties);

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("reason");
    }

    private Health health(EnsAuditProperties properties) {
        sinks = new AuditSinks(properties, Clock.systemUTC());
        sinks.configure();
        return new AuditHealthIndicator(properties, sinks).health();
    }
}

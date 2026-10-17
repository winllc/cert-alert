package com.winllc.certalert.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * Whether the audit trail can be written, for the readiness probe ({@code auditLog}).
 *
 * <p>The trail itself is in the database, which has a health check of its own; this is the
 * JSONL file output ({@code ENS_AUDIT_FILE}), when there is one. A sign-in is refused if its
 * audit record cannot be written, so an instance whose audit file has gone read-only - a full
 * or unmounted volume - is not one to send people to. The forwarding outputs (Splunk,
 * syslog) are not here: they catch up from the database once the far end is back, and an
 * outage there is no reason to stop serving.
 */
@Component("auditLogHealthIndicator")
public class AuditHealthIndicator extends AbstractHealthIndicator {
    // Named so the contributor is "auditLog" (Boot drops the HealthIndicator suffix) without
    // taking the AuditLog service's bean name.

    private final EnsAuditProperties properties;
    private final AuditSinks sinks;

    public AuditHealthIndicator(EnsAuditProperties properties, AuditSinks sinks) {
        super("The audit log could not be checked");
        this.properties = properties;
        this.sinks = sinks;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        String file = properties.getFile();
        if (file == null || file.isBlank()) {
            builder.up().withDetail("file", "not configured");
            return;
        }
        Path path = Path.of(file).toAbsolutePath();
        Path directory = path.getParent();
        boolean writable = Files.exists(path)
                ? Files.isRegularFile(path) && Files.isWritable(path)
                : directory != null && Files.isDirectory(directory) && Files.isWritable(directory);
        AuditSinks.OutputStatus output = sinks.statuses().stream()
                .filter(status -> "file".equals(status.name()))
                .findFirst()
                .orElse(null);
        boolean enabled = output != null && output.enabled();
        builder.withDetail("file", path.toString());
        if (!writable || !enabled) {
            builder.down().withDetail("reason", !enabled
                    ? "the file output could not be opened" + (output != null && output.lastError() != null
                            ? ": " + output.lastError() : "")
                    : "not writable");
            return;
        }
        builder.up();
    }
}

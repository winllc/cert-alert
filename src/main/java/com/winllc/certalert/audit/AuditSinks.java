package com.winllc.certalert.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The synchronous outputs: stdout and the JSONL file, both through the dedicated
 * {@code ens.audit} logger.
 *
 * <p>That logger carries its own appenders, configured here from {@code ens.audit.*}, with
 * the pattern {@code %msg%n} - one raw JSON line per event, no timestamp or level in front -
 * and {@code additivity=false}, so the application's own log format never wraps it and the
 * application's log never repeats it. Docker's logging driver or a Splunk forwarder picks it
 * up from stdout or from the file.
 *
 * <p>Written after the database commit; a failure here is logged and never undoes the event,
 * which the store already holds and the forwarders will still deliver.
 */
@Component
public class AuditSinks {

    public static final String LOGGER = "ens.audit";

    private static final String STDOUT_APPENDER = "ENS_AUDIT_STDOUT";
    private static final String FILE_APPENDER = "ENS_AUDIT_FILE";

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(AuditSinks.class);

    private final EnsAuditProperties properties;
    private final Clock clock;

    private final OutputStatus stdout = new OutputStatus("stdout");
    private final OutputStatus file = new OutputStatus("file");

    public AuditSinks(EnsAuditProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @PostConstruct
    void configure() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
            log.warn("Audit stdout and file outputs need Logback; neither is available");
            return;
        }
        Logger logger = context.getLogger(LOGGER);
        logger.setAdditive(false);
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        detach(logger, STDOUT_APPENDER);
        detach(logger, FILE_APPENDER);

        stdout.enabled = properties.isStdout();
        if (stdout.enabled) {
            ConsoleAppender<ILoggingEvent> console = new ConsoleAppender<>();
            console.setContext(context);
            console.setName(STDOUT_APPENDER);
            console.setEncoder(encoder(context));
            console.start();
            logger.addAppender(console);
        }

        String path = properties.getFile();
        file.enabled = path != null && !path.isBlank();
        if (file.enabled) {
            try {
                Path target = Path.of(path).toAbsolutePath();
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                RollingFileAppender<ILoggingEvent> rolling = new RollingFileAppender<>();
                rolling.setContext(context);
                rolling.setName(FILE_APPENDER);
                rolling.setFile(target.toString());
                rolling.setAppend(true);

                SizeAndTimeBasedRollingPolicy<ILoggingEvent> policy = new SizeAndTimeBasedRollingPolicy<>();
                policy.setContext(context);
                policy.setParent(rolling);
                policy.setFileNamePattern(rotatedPattern(target.toString()));
                policy.setMaxFileSize(FileSize.valueOf("100MB"));
                policy.setMaxHistory(Math.max(1, properties.getFileKeep()));
                policy.start();

                rolling.setRollingPolicy(policy);
                rolling.setEncoder(encoder(context));
                rolling.start();
                logger.addAppender(rolling);
            } catch (Exception e) {
                file.failed("Could not open " + path + ": " + e.getMessage(), Instant.now(clock));
                log.error("Audit file output {} could not be opened; it is off until restart", path, e);
            }
        }
        log.info("Audit output stdout: {}", stdout.enabled ? "enabled (logger ens.audit)" : "disabled");
        log.info("Audit output file: {}", file.enabled
                ? "enabled, " + path + ", rotated daily or at 100 MB, keeping " + properties.getFileKeep()
                : "disabled");
    }

    @PreDestroy
    void stop() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            Logger logger = context.getLogger(LOGGER);
            detach(logger, FILE_APPENDER);
        }
    }

    /** Writes committed lines, in seq order. */
    void emit(List<String> lines, long lastSeq) {
        if (lines.isEmpty() || (!stdout.enabled && !file.enabled)) {
            return;
        }
        Instant now = Instant.now(clock);
        try {
            org.slf4j.Logger audit = LoggerFactory.getLogger(LOGGER);
            for (String line : lines) {
                audit.info(line);
            }
            if (stdout.enabled) {
                stdout.delivered(lastSeq, now);
            }
            if (file.enabled) {
                file.delivered(lastSeq, now);
            }
        } catch (RuntimeException e) {
            stdout.failed(e.toString(), now);
            file.failed(e.toString(), now);
            log.error("Could not write {} audit line(s) to stdout/file; the database holds them", lines.size(), e);
        }
    }

    List<OutputStatus> statuses() {
        return List.of(stdout, file);
    }

    private static PatternLayoutEncoder encoder(LoggerContext context) {
        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%msg%n");
        encoder.setCharset(java.nio.charset.StandardCharsets.UTF_8);
        encoder.start();
        return encoder;
    }

    /** {@code /var/log/ens/certalert/audit.jsonl} rotates to {@code audit.2026-10-16.0.jsonl}. */
    static String rotatedPattern(String file) {
        int slash = file.lastIndexOf('/');
        int dot = file.lastIndexOf('.');
        if (dot > slash + 1) {
            return file.substring(0, dot) + ".%d{yyyy-MM-dd}.%i" + file.substring(dot);
        }
        return file + ".%d{yyyy-MM-dd}.%i";
    }

    private static void detach(Logger logger, String name) {
        Appender<ILoggingEvent> existing = logger.getAppender(name);
        if (existing != null) {
            logger.detachAppender(existing);
            existing.stop();
        }
    }

    /** What the delivery panel shows for one output. */
    public static final class OutputStatus {
        private final String name;
        volatile boolean enabled;
        private volatile long lastSeq;
        private volatile Instant deliveredAt;
        private volatile String lastError;
        private volatile Instant lastErrorAt;

        OutputStatus(String name) {
            this.name = name;
        }

        void delivered(long seq, Instant at) {
            lastSeq = Math.max(lastSeq, seq);
            deliveredAt = at;
        }

        void failed(String error, Instant at) {
            lastError = error;
            lastErrorAt = at;
        }

        public String name() {
            return name;
        }

        public boolean enabled() {
            return enabled;
        }

        public long lastSeq() {
            return lastSeq;
        }

        public Instant deliveredAt() {
            return deliveredAt;
        }

        public String lastError() {
            return lastError;
        }

        public Instant lastErrorAt() {
            return lastErrorAt;
        }
    }
}

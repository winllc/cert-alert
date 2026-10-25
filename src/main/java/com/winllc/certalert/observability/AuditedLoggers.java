package com.winllc.certalert.observability;

import com.winllc.certalert.audit.AuditLog;
import com.winllc.certalert.audit.AuditRecord;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.actuate.logging.LoggersEndpoint;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerGroups;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The {@code loggers} actuator endpoint, with every change of a level in the audit trail.
 *
 * <p>An administrator may still turn a logger up or down at run time - DEBUG on the
 * directory client is how a sign-in problem is found - but turning logging off is also a way
 * to hide what happens next, so who did it, to which logger and from what level to what is
 * recorded as {@code logging.level-changed}, before the change takes effect. If the trail
 * cannot be written, the level is left as it was.
 *
 * <p>Replaces Spring Boot's own endpoint bean, which steps aside for one of the same type.
 */
@Configuration(proxyBeanMethods = false)
public class AuditedLoggers {

    public static final String ACTION = "logging.level-changed";

    @Bean
    public LoggersEndpoint loggersEndpoint(
            LoggingSystem loggingSystem, ObjectProvider<LoggerGroups> loggerGroups, AuditLog audit) {
        return new Endpoint(loggingSystem, loggerGroups.getIfAvailable(LoggerGroups::new), audit);
    }

    static final class Endpoint extends LoggersEndpoint {

        private final AuditLog audit;

        Endpoint(LoggingSystem loggingSystem, LoggerGroups loggerGroups, AuditLog audit) {
            super(loggingSystem, loggerGroups);
            this.audit = audit;
        }

        @Override
        @WriteOperation
        public void configureLogLevel(@Selector String name, @Nullable LogLevel configuredLevel) {
            LoggerLevelsDescriptor current = loggerLevels(name);
            String before = current == null ? null : current.getConfiguredLevel();
            audit.record(AuditRecord.of(AuditRecord.Category.CONFIGURATION, ACTION)
                    .severity(AuditRecord.Severity.NOTICE)
                    .target("logger", name, name)
                    .change("level", before, configuredLevel == null ? null : configuredLevel.name()));
            super.configureLogLevel(name, configuredLevel);
        }
    }
}

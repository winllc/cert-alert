package com.winllc.certalert.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.slf4j.LoggerFactory;

/** Captures what the {@code ens.audit} logger - the stdout and file outputs - emits. */
final class AuditLines implements AutoCloseable {

    private final Logger logger = (Logger) LoggerFactory.getLogger(AuditSinks.LOGGER);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    AuditLines() {
        appender.setName("TEST_CAPTURE");
        appender.start();
        logger.addAppender(appender);
    }

    List<String> lines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    String all() {
        return String.join("\n", lines());
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}

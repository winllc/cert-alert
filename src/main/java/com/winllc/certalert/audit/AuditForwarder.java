package com.winllc.certalert.audit;

import java.util.List;

/**
 * An output that delivers from the store in the background: Splunk HEC, syslog. Reads by a
 * persisted cursor, so delivery is at-least-once and survives restarts.
 */
public interface AuditForwarder extends AutoCloseable {

    /** Its name in the delivery panel and the cursor table. */
    String name();

    /** Where it delivers, for the startup log - never including a credential. */
    String describe();

    /** Delivers these events, in order, or throws. All or nothing as far as the cursor is concerned. */
    void send(List<StoredEvent> events) throws Exception;

    /** Most events it takes in one go. */
    default int batchSize() {
        return 100;
    }

    @Override
    default void close() {}
}

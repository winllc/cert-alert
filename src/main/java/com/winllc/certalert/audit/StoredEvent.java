package com.winllc.certalert.audit;

import java.time.Instant;

/** One event as the store holds it: its position, a few fields to route by, and the line. */
public record StoredEvent(long seq, Instant time, String action, String severity, String line) {}

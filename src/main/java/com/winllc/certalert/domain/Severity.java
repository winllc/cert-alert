package com.winllc.certalert.domain;

/** How urgent an alert is, derived from the check status and remaining validity. */
public enum Severity {
    INFO,
    WARNING,
    CRITICAL
}

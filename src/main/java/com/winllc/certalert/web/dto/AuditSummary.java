package com.winllc.certalert.web.dto;

import java.time.Instant;

/**
 * What the administration page says above the log: how much of it there is, and how much of
 * it is recent.
 *
 * @param total every record kept
 * @param lastDay how many in the last 24 hours, which is what a quiet night looks like
 * @param lastWeek and in the last seven days
 * @param actors how many distinct people and jobs appear in it
 * @param oldest the earliest record, so it is obvious how far back the trail actually goes
 * @param newest the most recent, which says whether anything is running at all
 */
public record AuditSummary(
        long total, long lastDay, long lastWeek, long actors, Instant oldest, Instant newest) {}

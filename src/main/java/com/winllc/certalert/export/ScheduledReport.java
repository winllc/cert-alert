package com.winllc.certalert.export;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.List;

/** The inventory, narrowed by a filter, emailed as a file on a schedule. */
@Entity
@Table(name = "scheduled_report")
public class ScheduledReport {

    public enum Frequency {
        DAILY, WEEKLY, MONTHLY
    }

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "scheduled_report_seq")
    @SequenceGenerator(name = "scheduled_report_seq", sequenceName = "scheduled_report_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, length = 320)
    private String owner;

    /** The directory entry of whoever scheduled it, checked again at every run. */
    @Column(name = "owner_dn", length = 1000)
    private String ownerDn;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 2000)
    private String query;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private CertificateExport.Format format;

    @Column(nullable = false, length = 2000)
    private String recipients;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Frequency frequency;

    /** 1 (Monday) to 7, for a weekly report. */
    @Column(name = "day_of_week")
    private Integer dayOfWeek;

    /** 1 to 28, for a monthly one: every month has those days. */
    @Column(name = "day_of_month")
    private Integer dayOfMonth;

    @Column(name = "hour_utc", nullable = false)
    private int hourUtc;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "next_run_at", nullable = false)
    private Instant nextRunAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_rows")
    private Long lastRows;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    protected ScheduledReport() {
        // for JPA
    }

    ScheduledReport(
            String owner,
            String ownerDn,
            String name,
            String query,
            CertificateExport.Format format,
            List<String> recipients,
            Frequency frequency,
            Integer dayOfWeek,
            Integer dayOfMonth,
            int hourUtc,
            Instant now) {
        this.owner = owner;
        this.ownerDn = ownerDn;
        this.name = name;
        this.query = query;
        this.format = format;
        this.recipients = String.join(",", recipients);
        this.frequency = frequency;
        this.dayOfWeek = dayOfWeek;
        this.dayOfMonth = dayOfMonth;
        this.hourUtc = hourUtc;
        this.enabled = true;
        this.createdAt = now;
        this.nextRunAt = nextAfter(now);
    }

    /** The first time on this report's schedule strictly after {@code after}. */
    Instant nextAfter(Instant after) {
        LocalDateTime from = LocalDateTime.ofInstant(after, ZoneOffset.UTC);
        LocalDate day = from.toLocalDate();
        for (int i = 0; i < 400; i++) {
            LocalDate candidate = day.plusDays(i);
            boolean onSchedule = switch (frequency) {
                case DAILY -> true;
                case WEEKLY -> candidate.getDayOfWeek() == DayOfWeek.of(dayOfWeek);
                case MONTHLY -> candidate.getDayOfMonth() == dayOfMonth;
            };
            if (onSchedule) {
                Instant at = candidate.atTime(hourUtc, 0).toInstant(ZoneOffset.UTC);
                if (at.isAfter(after)) {
                    return at;
                }
            }
        }
        // Unreachable for a valid schedule; a month from now otherwise.
        return day.with(TemporalAdjusters.firstDayOfNextMonth()).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    void ran(Instant at, long rows, String error) {
        this.lastRunAt = at;
        this.lastRows = rows;
        this.lastError = error == null || error.length() <= 1000 ? error : error.substring(0, 1000);
        this.nextRunAt = nextAfter(at);
    }

    void enabled(boolean enabled, Instant now) {
        this.enabled = enabled;
        if (enabled) {
            this.nextRunAt = nextAfter(now);
        }
    }

    public Long getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public String getOwnerDn() {
        return ownerDn;
    }

    public String getName() {
        return name;
    }

    public String getQuery() {
        return query;
    }

    public CertificateExport.Format getFormat() {
        return format;
    }

    public List<String> getRecipients() {
        return Arrays.stream(recipients.split(",")).map(String::trim).filter(r -> !r.isEmpty()).toList();
    }

    public Frequency getFrequency() {
        return frequency;
    }

    public Integer getDayOfWeek() {
        return dayOfWeek;
    }

    public Integer getDayOfMonth() {
        return dayOfMonth;
    }

    public int getHourUtc() {
        return hourUtc;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public Long getLastRows() {
        return lastRows;
    }

    public String getLastError() {
        return lastError;
    }
}

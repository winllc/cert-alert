package com.winllc.certalert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Where the changelog connector has read up to.
 *
 * <p>Held in the database rather than in memory so the connector resumes after a restart
 * instead of replaying the directory's whole history or silently skipping whatever changed
 * while it was down.
 */
@Entity
@Table(
        name = "changelog_cursor",
        uniqueConstraints = @UniqueConstraint(name = "uk_changelog_cursor_name", columnNames = "name"))
public class ChangelogCursor {

    /** There is one changelog, so there is one cursor; the name leaves room for more. */
    public static final String DEFAULT_NAME = "default";

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "changelog_cursor_seq")
    @SequenceGenerator(name = "changelog_cursor_seq", sequenceName = "changelog_cursor_seq", allocationSize = 1)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "last_change_number", nullable = false)
    private long lastChangeNumber;

    @Column(name = "first_available_number")
    private Long firstAvailableNumber;

    @Column(name = "last_available_number")
    private Long lastAvailableNumber;

    @Column(name = "changes_applied", nullable = false)
    private long changesApplied;

    @Column(name = "changes_ignored", nullable = false)
    private long changesIgnored;

    @Column(nullable = false)
    private long errors;

    @Column(name = "gaps_detected", nullable = false)
    private long gapsDetected;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "last_polled_at")
    private Instant lastPolledAt;

    @Column(name = "last_change_at")
    private Instant lastChangeAt;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Version
    @Column(nullable = false)
    private long version;

    protected ChangelogCursor() {
        // for JPA
    }

    public ChangelogCursor(String name, long lastChangeNumber, Instant startedAt) {
        this.name = name;
        this.lastChangeNumber = lastChangeNumber;
        this.startedAt = startedAt;
    }

    /** Advances past a change that has been applied, or deliberately ignored. */
    public void advanceTo(long changeNumber, Instant at, boolean applied) {
        this.lastChangeNumber = changeNumber;
        this.lastChangeAt = at;
        if (applied) {
            this.changesApplied++;
        } else {
            this.changesIgnored++;
        }
    }

    public void recordPoll(Instant at, Long firstAvailable, Long lastAvailable) {
        this.lastPolledAt = at;
        this.firstAvailableNumber = firstAvailable;
        this.lastAvailableNumber = lastAvailable;
        this.lastError = null;
    }

    public void recordError(String message) {
        this.errors++;
        this.lastError = message == null || message.length() <= 2000 ? message : message.substring(0, 1997) + "...";
    }

    /**
     * Records that the directory has discarded changes this cursor had not reached, and
     * jumps to where its history now begins.
     */
    public void recordGap(long firstAvailableNumber) {
        this.gapsDetected++;
        this.lastChangeNumber = firstAvailableNumber - 1;
    }

    /** How far behind the directory this cursor is, where the directory reported a bound. */
    public Long lag() {
        return lastAvailableNumber == null ? null : Math.max(0, lastAvailableNumber - lastChangeNumber);
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getLastChangeNumber() {
        return lastChangeNumber;
    }

    public Long getFirstAvailableNumber() {
        return firstAvailableNumber;
    }

    public Long getLastAvailableNumber() {
        return lastAvailableNumber;
    }

    public long getChangesApplied() {
        return changesApplied;
    }

    public long getChangesIgnored() {
        return changesIgnored;
    }

    public long getErrors() {
        return errors;
    }

    public long getGapsDetected() {
        return gapsDetected;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getLastPolledAt() {
        return lastPolledAt;
    }

    public Instant getLastChangeAt() {
        return lastChangeAt;
    }

    public String getLastError() {
        return lastError;
    }
}

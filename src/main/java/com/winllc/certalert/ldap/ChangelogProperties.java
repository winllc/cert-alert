package com.winllc.certalert.ldap;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The changelog connector, bound from {@code cert-alert.ldap.changelog}.
 *
 * <p>Defaults follow the changelog most directories derived from the Netscape line publish
 * (draft-good-ldap-changelog): a {@code cn=changelog} suffix of {@code changeLogEntry}
 * objects, each carrying a monotonic {@code changeNumber}, the {@code targetDN} that
 * changed, and a {@code changeType}. Confirm the names against your directory - and that
 * the account the application binds as is allowed to read the suffix at all, which is often
 * a separate grant.
 *
 * <p>Off by default. A directory that publishes no changelog would leave the connector
 * erroring on a loop, and the scheduled sweeps already keep the cache correct.
 */
@ConfigurationProperties(prefix = "cert-alert.ldap.changelog")
public class ChangelogProperties {

    private boolean enabled = false;

    /**
     * Whether the loop starts with the application.
     *
     * <p>Turn it off to have the connector present but driven by hand - through the API, or
     * by a test - or to deploy the same image across several instances with only one of them
     * following the changelog.
     */
    private boolean autoStart = true;

    /** Absolute DN of the changelog suffix. It sits outside the directory's data tree. */
    private String baseDn = "cn=changelog";

    /**
     * Filter for a batch of changes; {@code {0}} is replaced with the first change number
     * wanted. The connector re-checks the number of every entry it reads regardless, so a
     * directory whose index or matching rule is loose cannot make it repeat or skip work.
     */
    private String filter = "(&(objectClass=changeLogEntry)(changeNumber>={0}))";

    /** How long to wait after a poll that found nothing. */
    private Duration pollInterval = Duration.ofSeconds(10);

    /** Most changes read in one poll. */
    private int batchSize = 500;

    /** Where to begin when there is no stored position. */
    private StartPosition startFrom = StartPosition.LATEST;

    /**
     * Whether the first poll with no stored position reads the whole tree before it starts
     * following.
     *
     * <p>It should. Following the changelog only keeps a cache current; it never populates
     * one. With {@link StartPosition#LATEST} the connector parks at the directory's newest
     * change and applies what happens from then on, so switching it on against an empty
     * cache and nothing else would leave that cache empty until the nightly sweep. This
     * makes enabling the connector sufficient on its own.
     *
     * <p>It runs once, when there is no cursor - not on every restart. Turn it off where the
     * baseline is established some other way and a full read at startup is not wanted.
     */
    private boolean fullSyncOnFirstRun = true;

    /**
     * Whether discovering that the directory has discarded changes this connector had not
     * reached should trigger a full sweep. It should: the alternative is carrying on with a
     * cache that is quietly missing whatever was in the gap.
     */
    private boolean fullSyncOnGap = true;

    /** Longest backoff between retries while the directory is unreachable. */
    private Duration maxBackoff = Duration.ofMinutes(5);

    // Attribute names on a changeLogEntry.
    private String changeNumberAttribute = "changeNumber";
    private String targetDnAttribute = "targetDN";
    private String changeTypeAttribute = "changeType";
    private String newRdnAttribute = "newRDN";
    private String newSuperiorAttribute = "newSuperior";

    /** Root DSE attributes advertising the range the directory still holds. */
    private String firstChangeNumberAttribute = "firstChangeNumber";
    private String lastChangeNumberAttribute = "lastChangeNumber";

    /** Where to start reading when nothing has been read before. */
    public enum StartPosition {
        /**
         * Only changes made from now on. The right default: the initial state of the cache
         * comes from a full sweep - {@link #isFullSyncOnFirstRun()} runs one - and replaying
         * the directory's entire history to reach the same place would be pointless work.
         */
        LATEST,

        /** Everything the changelog still holds. */
        BEGINNING
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    public void setAutoStart(boolean autoStart) {
        this.autoStart = autoStart;
    }

    public String getBaseDn() {
        return baseDn;
    }

    public void setBaseDn(String baseDn) {
        this.baseDn = baseDn;
    }

    public String getFilter() {
        return filter;
    }

    public void setFilter(String filter) {
        this.filter = filter;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public StartPosition getStartFrom() {
        return startFrom;
    }

    public void setStartFrom(StartPosition startFrom) {
        this.startFrom = startFrom;
    }

    public boolean isFullSyncOnFirstRun() {
        return fullSyncOnFirstRun;
    }

    public void setFullSyncOnFirstRun(boolean fullSyncOnFirstRun) {
        this.fullSyncOnFirstRun = fullSyncOnFirstRun;
    }

    public boolean isFullSyncOnGap() {
        return fullSyncOnGap;
    }

    public void setFullSyncOnGap(boolean fullSyncOnGap) {
        this.fullSyncOnGap = fullSyncOnGap;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    public String getChangeNumberAttribute() {
        return changeNumberAttribute;
    }

    public void setChangeNumberAttribute(String changeNumberAttribute) {
        this.changeNumberAttribute = changeNumberAttribute;
    }

    public String getTargetDnAttribute() {
        return targetDnAttribute;
    }

    public void setTargetDnAttribute(String targetDnAttribute) {
        this.targetDnAttribute = targetDnAttribute;
    }

    public String getChangeTypeAttribute() {
        return changeTypeAttribute;
    }

    public void setChangeTypeAttribute(String changeTypeAttribute) {
        this.changeTypeAttribute = changeTypeAttribute;
    }

    public String getNewRdnAttribute() {
        return newRdnAttribute;
    }

    public void setNewRdnAttribute(String newRdnAttribute) {
        this.newRdnAttribute = newRdnAttribute;
    }

    public String getNewSuperiorAttribute() {
        return newSuperiorAttribute;
    }

    public void setNewSuperiorAttribute(String newSuperiorAttribute) {
        this.newSuperiorAttribute = newSuperiorAttribute;
    }

    public String getFirstChangeNumberAttribute() {
        return firstChangeNumberAttribute;
    }

    public void setFirstChangeNumberAttribute(String firstChangeNumberAttribute) {
        this.firstChangeNumberAttribute = firstChangeNumberAttribute;
    }

    public String getLastChangeNumberAttribute() {
        return lastChangeNumberAttribute;
    }

    public void setLastChangeNumberAttribute(String lastChangeNumberAttribute) {
        this.lastChangeNumberAttribute = lastChangeNumberAttribute;
    }
}

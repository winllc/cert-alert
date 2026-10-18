package com.winllc.certalert.cluster;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Running more than one replica, bound from {@code cert-alert.cluster}.
 *
 * <p>Nothing here needs setting for one replica: the locks and the run records work the same
 * with one as with several, and the defaults suit both.
 */
@ConfigurationProperties(prefix = "cert-alert.cluster")
public class ClusterProperties {

    /**
     * How long a job's lock lasts before it is renewed. The replica holding it renews it at
     * half this while the job runs, however long that is; a replica that dies holding it
     * releases it when this has passed, and another may then run the job. Not less than 30
     * seconds.
     */
    private Duration lockAtMostFor = Duration.ofMinutes(10);

    /** How long a run started by hand is kept, for the page that asked after it. */
    private Duration runRetention = Duration.ofDays(7);

    /**
     * How long a run started by hand may go without its replica saying it is still at work
     * before it is marked failed: the replica was stopped, or died, mid-run.
     */
    private Duration staleRunAfter = Duration.ofMinutes(5);

    public Duration getLockAtMostFor() {
        return lockAtMostFor;
    }

    public void setLockAtMostFor(Duration lockAtMostFor) {
        this.lockAtMostFor = lockAtMostFor;
    }

    public Duration getRunRetention() {
        return runRetention;
    }

    public void setRunRetention(Duration runRetention) {
        this.runRetention = runRetention;
    }

    public Duration getStaleRunAfter() {
        return staleRunAfter;
    }

    public void setStaleRunAfter(Duration staleRunAfter) {
        this.staleRunAfter = staleRunAfter;
    }
}

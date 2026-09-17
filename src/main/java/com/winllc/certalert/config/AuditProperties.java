package com.winllc.certalert.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The audit trail, bound from {@code cert-alert.audit}. */
@ConfigurationProperties(prefix = "cert-alert.audit")
public class AuditProperties {

    /**
     * Turning this off stops records being written. It does not delete what is already
     * there, and the history stays readable.
     */
    private boolean enabled = true;

    private final Retention retention = new Retention();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Retention getRetention() {
        return retention;
    }

    /** Trimming old records. */
    public static class Retention {

        /**
         * Off by default: an audit trail that quietly deletes itself is worse than one that
         * grows. Turn it on once you have decided how long you are keeping.
         */
        private boolean enabled = false;

        /** Records older than this are removed once retention is on. */
        private Duration after = Duration.ofDays(365);

        private String cron = "0 30 3 * * SUN";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getAfter() {
            return after;
        }

        public void setAfter(Duration after) {
            this.after = after;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }
    }
}

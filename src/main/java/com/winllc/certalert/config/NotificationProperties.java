package com.winllc.certalert.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** The notification module, bound from {@code cert-alert.notifications}. */
@ConfigurationProperties(prefix = "cert-alert.notifications")
public class NotificationProperties {

    /**
     * Whether people are told at all. Turning this off leaves the alert channels alone -
     * they are how the operators hear - and stops the per-person copies being written.
     */
    private boolean enabled = true;

    /**
     * How long to wait before telling somebody the same thing again. A sweep runs nightly
     * and a certificate stays expiring for a month; without this they would hear about it
     * thirty times.
     */
    private Duration repeatAfter = Duration.ofDays(7);

    private final Digest digest = new Digest();
    private final Email email = new Email();
    private final Retention retention = new Retention();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getRepeatAfter() {
        return repeatAfter;
    }

    public void setRepeatAfter(Duration repeatAfter) {
        this.repeatAfter = repeatAfter;
    }

    public Digest getDigest() {
        return digest;
    }

    public Email getEmail() {
        return email;
    }

    public Retention getRetention() {
        return retention;
    }

    /** The scheduled round-up of what is expiring. */
    public static class Digest {

        private boolean enabled = true;

        /** Daily at 07:00 UTC: early enough to act on, late enough to have swept overnight. */
        private String cron = "0 0 7 * * *";

        /** How far ahead to look. Matches the warning window unless told otherwise. */
        private Duration window = Duration.ofDays(30);

        /** Whether already-expired certificates are included. They usually still matter. */
        private boolean includeExpired = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public boolean isIncludeExpired() {
            return includeExpired;
        }

        public void setIncludeExpired(boolean includeExpired) {
            this.includeExpired = includeExpired;
        }
    }

    /**
     * Sending notifications on by email. Separate from {@code cert-alert.alerts.email},
     * which tells a fixed list of operators about every alert; this writes to the person
     * the certificate is actually somebody's responsibility to renew.
     */
    public static class Email {

        /** Off by default. Requires {@code spring.mail.*} to be configured. */
        private boolean enabled = false;

        private String from = "cert-alert@localhost";

        private String subjectPrefix = "[cert-alert] ";

        /** How many to send in one run, so a first run cannot spend an hour in the mailer. */
        private int maxPerRun = 500;

        /**
         * Render every message and send none of them.
         *
         * <p>For the question nobody wants to answer by finding out: switching this on for
         * the first time against a real directory, how many people hear from it, and does
         * what they get read the way it was meant to? A round-up run this way reports who
         * it would have written to and what the message says, and puts nothing in anybody's
         * inbox.
         *
         * <p>The messages are built exactly as they would be otherwise - same templates,
         * same model, same subject line - and dropped at the point the transport would have
         * been handed one. A rendering mistake still surfaces here rather than in the first
         * real run.
         */
        private boolean dryRun = false;

        /**
         * A directory of email templates to use in place of the packaged ones.
         *
         * <p>The wording of these messages is a deployment's own - who signs them, what an
         * internal renewal process is called, what somebody is meant to do next - and none
         * of that belongs in an image everybody shares. Point this at a directory and the
         * templates in it are used; anything not in it falls back to the packaged one, so
         * overriding the subject line of one message does not mean maintaining all four.
         *
         * <p>The layout mirrors the jar, so the files live in an {@code email}
         * subdirectory: {@code <directory>/email/expiring-user.html} and so on. Read once
         * and cached, so a change to a mounted file takes effect on the next restart.
         */
        private String templateDirectory;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getFrom() {
            return from;
        }

        public void setFrom(String from) {
            this.from = from;
        }

        public String getSubjectPrefix() {
            return subjectPrefix;
        }

        public void setSubjectPrefix(String subjectPrefix) {
            this.subjectPrefix = subjectPrefix;
        }

        public int getMaxPerRun() {
            return maxPerRun;
        }

        public void setMaxPerRun(int maxPerRun) {
            this.maxPerRun = maxPerRun;
        }

        public boolean isDryRun() {
            return dryRun;
        }

        public void setDryRun(boolean dryRun) {
            this.dryRun = dryRun;
        }

        public String getTemplateDirectory() {
            return templateDirectory;
        }

        public void setTemplateDirectory(String templateDirectory) {
            this.templateDirectory = templateDirectory;
        }
    }

    /** Clearing out what has been read. */
    public static class Retention {

        /** Off by default, like every other thing here that deletes. */
        private boolean enabled = false;

        private Duration after = Duration.ofDays(90);

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
    }
}

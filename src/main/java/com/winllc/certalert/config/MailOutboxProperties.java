package com.winllc.certalert.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How email waiting in the outbox is sent, bound from {@code cert-alert.mail.outbox}.
 *
 * <p>Every message - a round-up, an alert - is written to the outbox in the transaction that
 * decided to send it, and sent once that commits. One that fails is tried again, further
 * apart each time, until it is old enough that sending it would be worse than not.
 */
@ConfigurationProperties(prefix = "cert-alert.mail.outbox")
public class MailOutboxProperties {

    /**
     * Whether waiting mail is sent in the background: straight after the transaction that
     * queued it, and on {@link #pollInterval} for retries. Tests switch it off and send
     * explicitly; a deployment has no reason to.
     */
    private boolean background = true;

    /** How often the outbox is looked at for mail that is due. */
    private Duration pollInterval = Duration.ofSeconds(30);

    /** The wait after the first failure; it doubles with each one after. */
    private Duration retryInitial = Duration.ofMinutes(1);

    /** The longest wait between two attempts. */
    private Duration retryMax = Duration.ofHours(1);

    /**
     * How long a message keeps being tried. Long enough to see out a mail server that is
     * down for a weekend; a round-up older than this has been replaced by newer ones anyway.
     */
    private Duration giveUpAfter = Duration.ofDays(3);

    /** How long sent, given-up and replaced messages are kept before they are deleted. */
    private Duration keep = Duration.ofDays(7);

    public boolean isBackground() {
        return background;
    }

    public void setBackground(boolean background) {
        this.background = background;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getRetryInitial() {
        return retryInitial;
    }

    public void setRetryInitial(Duration retryInitial) {
        this.retryInitial = retryInitial;
    }

    public Duration getRetryMax() {
        return retryMax;
    }

    public void setRetryMax(Duration retryMax) {
        this.retryMax = retryMax;
    }

    public Duration getGiveUpAfter() {
        return giveUpAfter;
    }

    public void setGiveUpAfter(Duration giveUpAfter) {
        this.giveUpAfter = giveUpAfter;
    }

    public Duration getKeep() {
        return keep;
    }

    public void setKeep(Duration keep) {
        this.keep = keep;
    }
}

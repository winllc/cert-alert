package com.winllc.certalert.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What counts as one issuance, bound from {@code cert-alert.credentials}.
 *
 * <p>A person is issued two certificates at once - one to sign with, one to be encrypted
 * to - and they arrive with all but identical dates. "All but" is the point: a CA signs
 * them seconds or minutes apart, sometimes on either side of midnight, so the pair is
 * recognised by proximity rather than equality.
 */
@ConfigurationProperties(prefix = "cert-alert.credentials")
public class CredentialProperties {

    /**
     * How far apart a person's signing and encryption certificates may be issued and still
     * be the same issuance. Wider than a CA needs, because the question being asked is "was
     * this one renewal or two", and a week of slack answers it without false alarms.
     */
    private Duration pairWindow = Duration.ofDays(7);

    public Duration getPairWindow() {
        return pairWindow;
    }

    public void setPairWindow(Duration pairWindow) {
        this.pairWindow = pairWindow;
    }
}

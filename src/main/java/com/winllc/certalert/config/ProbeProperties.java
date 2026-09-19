package com.winllc.certalert.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The endpoint probe, bound from {@code cert-alert.probe}.
 *
 * <p>The only outbound connection this application makes that is not to the directory or
 * the mail server, and the only one somebody asks for by pressing a button - which is why
 * it can be switched off wholesale on a network where reaching a server from here is not
 * something the application should be doing.
 */
@ConfigurationProperties(prefix = "cert-alert.probe")
public class ProbeProperties {

    /** Whether a probe may be run at all. Off removes the card from the page too. */
    private boolean enabled = true;

    /** The port offered when the entry's URL does not name one. */
    private int defaultPort = 443;

    /** How long to wait for the connection. Short: an endpoint that is down is the answer. */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** How long to wait for the handshake once connected. */
    private Duration readTimeout = Duration.ofSeconds(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getDefaultPort() {
        return defaultPort;
    }

    public void setDefaultPort(int defaultPort) {
        this.defaultPort = defaultPort;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}

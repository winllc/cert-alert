package com.winllc.certalert.alert;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings for the email alert channel, bound from {@code cert-alert.alerts.email}. */
@ConfigurationProperties(prefix = "cert-alert.alerts.email")
public class EmailAlertProperties {

    /** Whether alerts are emailed. Requires {@code spring.mail.*} to be configured. */
    private boolean enabled = false;

    /** Envelope sender address. */
    private String from = "cert-alert@localhost";

    /** Recipients of every alert. */
    private List<String> to = new ArrayList<>();

    /** Prefix prepended to the subject line. */
    private String subjectPrefix = "[cert-alert] ";

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

    public List<String> getTo() {
        return to;
    }

    public void setTo(List<String> to) {
        this.to = to;
    }

    public String getSubjectPrefix() {
        return subjectPrefix;
    }

    public void setSubjectPrefix(String subjectPrefix) {
        this.subjectPrefix = subjectPrefix;
    }
}

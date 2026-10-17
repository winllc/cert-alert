package com.winllc.certalert.audit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the audit trail goes besides the database, bound from {@code ens.audit} - which
 * {@code application.yml} fills from the shared {@code ENS_AUDIT_*} environment variables.
 * The database is always written; every output here is optional.
 */
@ConfigurationProperties(prefix = "ens.audit")
public class EnsAuditProperties {

    /** {@code ENS_AUDIT_STDOUT}: each event as a raw JSON line on the {@code ens.audit} logger, to stdout. */
    private boolean stdout = true;

    /** {@code ENS_AUDIT_FILE}: a JSONL file, rotated daily or at 100 MB. Off when empty. */
    private String file = "";

    /** {@code ENS_AUDIT_FILE_KEEP}: how many rotated files to keep. */
    private int fileKeep = 30;

    /**
     * {@code ENS_AUDIT_RETENTION_DAYS}: events older than this are pruned once every
     * forwarding output has delivered them. 0 keeps them forever. The older
     * {@code cert-alert.audit.retention.*} settings still apply when this is 0.
     */
    private int retentionDays = 0;

    /** Whether the forwarders and the denial roll-up run in the background. Tests drive them by hand. */
    private boolean background = true;

    /** How often an idle forwarder looks for new events. */
    private Duration pollInterval = Duration.ofSeconds(5);

    /** How long an output may fail before {@code audit.delivery-failed} is recorded. */
    private Duration failureAlertAfter = Duration.ofMinutes(15);

    private final Splunk splunk = new Splunk();
    private final Syslog syslog = new Syslog();

    public boolean isStdout() {
        return stdout;
    }

    public void setStdout(boolean stdout) {
        this.stdout = stdout;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file == null ? "" : file.trim();
    }

    public int getFileKeep() {
        return fileKeep;
    }

    public void setFileKeep(int fileKeep) {
        this.fileKeep = fileKeep;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

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

    public Duration getFailureAlertAfter() {
        return failureAlertAfter;
    }

    public void setFailureAlertAfter(Duration failureAlertAfter) {
        this.failureAlertAfter = failureAlertAfter;
    }

    public Splunk getSplunk() {
        return splunk;
    }

    public Syslog getSyslog() {
        return syslog;
    }

    /** Splunk HTTP Event Collector. */
    public static class Splunk {

        /** {@code ENS_AUDIT_SPLUNK_URL}, e.g. {@code https://splunk.example:8088}. Off when empty. */
        private String url = "";

        /** {@code ENS_AUDIT_SPLUNK_TOKEN}. */
        private String token = "";

        /** {@code ENS_AUDIT_SPLUNK_TOKEN_FILE}: read when the token itself is not given. */
        private String tokenFile = "";

        /** {@code ENS_AUDIT_SPLUNK_INDEX}, optional. */
        private String index = "";

        /** {@code ENS_AUDIT_SPLUNK_SOURCETYPE}. */
        private String sourcetype = "ens:audit";

        /** {@code ENS_AUDIT_SPLUNK_INSECURE}: skip TLS verification. Test setups only. */
        private boolean insecure = false;

        public boolean isEnabled() {
            return url != null && !url.isBlank();
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url == null ? "" : url.trim();
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token == null ? "" : token.trim();
        }

        public String getTokenFile() {
            return tokenFile;
        }

        public void setTokenFile(String tokenFile) {
            this.tokenFile = tokenFile == null ? "" : tokenFile.trim();
        }

        public String getIndex() {
            return index;
        }

        public void setIndex(String index) {
            this.index = index == null ? "" : index.trim();
        }

        public String getSourcetype() {
            return sourcetype;
        }

        public void setSourcetype(String sourcetype) {
            this.sourcetype = sourcetype == null || sourcetype.isBlank() ? "ens:audit" : sourcetype.trim();
        }

        public boolean isInsecure() {
            return insecure;
        }

        public void setInsecure(boolean insecure) {
            this.insecure = insecure;
        }
    }

    /** RFC 5424 syslog. */
    public static class Syslog {

        /** {@code ENS_AUDIT_SYSLOG_HOST}. Off when empty. */
        private String host = "";

        /** {@code ENS_AUDIT_SYSLOG_PORT}: 514 for udp and tcp, 6514 for tls when unset. */
        private Integer port;

        /** {@code ENS_AUDIT_SYSLOG_PROTOCOL}: udp, tcp or tls. */
        private String protocol = "tcp";

        /** {@code ENS_AUDIT_SYSLOG_FACILITY}. */
        private String facility = "local0";

        public boolean isEnabled() {
            return host != null && !host.isBlank();
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host == null ? "" : host.trim();
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        /** The port to use: as set, or the protocol's default. */
        public int effectivePort() {
            if (port != null && port > 0) {
                return port;
            }
            return "tls".equalsIgnoreCase(protocol) ? 6514 : 514;
        }

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol == null || protocol.isBlank() ? "tcp" : protocol.trim().toLowerCase();
        }

        public String getFacility() {
            return facility;
        }

        public void setFacility(String facility) {
            this.facility = facility == null || facility.isBlank() ? "local0" : facility.trim().toLowerCase();
        }
    }
}

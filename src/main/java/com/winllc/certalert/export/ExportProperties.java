package com.winllc.certalert.export;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Exports and scheduled reports, bound from {@code cert-alert.exports}. */
@ConfigurationProperties(prefix = "cert-alert.exports")
public class ExportProperties {

    /** The most rows one download holds. */
    private int maxRows = 200_000;

    /** The most rows a scheduled report's attachment holds; beyond it the email says it was cut. */
    private int reportMaxRows = 50_000;

    /** How many scheduled reports one person may have. */
    private int maxReportsPerUser = 20;

    /**
     * The domains a scheduled report may be sent to ({@code example.gov}, or {@code *.mil} for
     * any below it). Empty allows any address.
     */
    private List<String> reportRecipientDomains = new ArrayList<>();

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public int getReportMaxRows() {
        return reportMaxRows;
    }

    public void setReportMaxRows(int reportMaxRows) {
        this.reportMaxRows = reportMaxRows;
    }

    public int getMaxReportsPerUser() {
        return maxReportsPerUser;
    }

    public void setMaxReportsPerUser(int maxReportsPerUser) {
        this.maxReportsPerUser = maxReportsPerUser;
    }

    public List<String> getReportRecipientDomains() {
        return reportRecipientDomains;
    }

    public void setReportRecipientDomains(List<String> reportRecipientDomains) {
        this.reportRecipientDomains = reportRecipientDomains;
    }
}

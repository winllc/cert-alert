package com.winllc.certalert.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The ENS platform baseline's web settings, bound from {@code ens}: the same names and
 * defaults in every ENS product, so an operator who has configured one can configure the
 * next from the same table. Each is set from its {@code ENS_*} environment variable in
 * application.yml.
 */
@ConfigurationProperties(prefix = "ens")
public class EnsSecurityProperties {

    private final Csp csp = new Csp();

    public Csp getCsp() {
        return csp;
    }

    /** The Content-Security-Policy every response carries. */
    public static class Csp {

        /**
         * {@code ENS_CSP_REPORT_ONLY}: send the policy as
         * {@code Content-Security-Policy-Report-Only}, so a browser reports what it would
         * have blocked rather than blocking it. The escape hatch for a site that finds a page
         * the tests missed; not a way to run.
         */
        private boolean reportOnly = false;

        public boolean isReportOnly() {
            return reportOnly;
        }

        public void setReportOnly(boolean reportOnly) {
            this.reportOnly = reportOnly;
        }
    }
}

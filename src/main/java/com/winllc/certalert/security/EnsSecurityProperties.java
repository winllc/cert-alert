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
    private final ConsentBanner consentBanner = new ConsentBanner();

    public Csp getCsp() {
        return csp;
    }

    public ConsentBanner getConsentBanner() {
        return consentBanner;
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

    /**
     * The notice-and-consent banner a DoD system shows before sign-in (DISA STIG). Off by
     * default; while on, nobody signs in by any means until their browser session has
     * acknowledged it. See {@code ConsentBanner}.
     */
    public static class ConsentBanner {

        /** {@code ENS_CONSENT_BANNER_ENABLED}. */
        private boolean enabled = false;

        /** {@code ENS_CONSENT_BANNER_TITLE}; empty means the standard USG title. */
        private String title;

        /**
         * {@code ENS_CONSENT_BANNER_TEXT}: plain text, blank lines between paragraphs, and
         * lines starting "- " a list. Empty means the standard DoD notice and consent text.
         */
        private String text;

        /** {@code ENS_CONSENT_BANNER_FILE}: the text from a file instead; wins over {@link #text}. */
        private String file;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public String getFile() {
            return file;
        }

        public void setFile(String file) {
            this.file = file;
        }
    }
}

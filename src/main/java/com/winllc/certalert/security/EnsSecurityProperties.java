package com.winllc.certalert.security;

import java.time.Duration;
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
    private final Session session = new Session();

    /**
     * {@code ENS_LICENSE_MENU_VISIBLE}: whether the navigation offers the license screen.
     * CertAlert's bar has no entry of its own for it; what plays that part is the "Manage the
     * license" link on the administration page, and that is what this hides. Hiding it changes
     * nothing else: the screen stays at {@code /admin/license} for whoever may open it, and the
     * license banners, the license-required page and the redirect an administrator gets while
     * nothing is licensed still lead there. A flat property on purpose: under {@code ens.license}
     * it would share a name with {@code ENS_LICENSE}, the license token, which then fails to bind.
     */
    private boolean licenseMenuVisible = true;

    public Csp getCsp() {
        return csp;
    }

    public ConsentBanner getConsentBanner() {
        return consentBanner;
    }

    public Session getSession() {
        return session;
    }

    public boolean isLicenseMenuVisible() {
        return licenseMenuVisible;
    }

    public void setLicenseMenuVisible(boolean licenseMenuVisible) {
        this.licenseMenuVisible = licenseMenuVisible;
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

    /** How long a signed-in session lasts, and how many one person may hold. See {@code SessionPolicy}. */
    public static class Session {

        /**
         * {@code ENS_SESSION_IDLE_TIMEOUT}: a session nobody has used for this long ends; 15
         * minutes by default, as the STIG asks.
         * {@code SERVER_SERVLET_SESSION_TIMEOUT} still works, as the same thing.
         */
        private Duration idleTimeout = Duration.ofMinutes(15);

        /**
         * {@code ENS_SESSION_ADMIN_IDLE_TIMEOUT}: the same for an administrator's session; unset
         * means {@link #idleTimeout}. The STIG suggests 15 minutes.
         */
        private Duration adminIdleTimeout;

        /** {@code ENS_SESSION_ABSOLUTE_TIMEOUT}: a session ends this long after sign-in, however busy. */
        private Duration absoluteTimeout = Duration.ofHours(12);

        /**
         * {@code ENS_SESSION_MAX_PER_USER}: sessions one person may hold at once; signing in
         * beyond it ends their oldest. 0 is no limit. The STIG suggests 3.
         */
        private int maxPerUser = 0;

        public Duration getIdleTimeout() {
            return idleTimeout;
        }

        public void setIdleTimeout(Duration idleTimeout) {
            this.idleTimeout = idleTimeout;
        }

        public Duration getAdminIdleTimeout() {
            return adminIdleTimeout;
        }

        public void setAdminIdleTimeout(Duration adminIdleTimeout) {
            this.adminIdleTimeout = adminIdleTimeout;
        }

        /** The administrators' idle timeout, which is the ordinary one unless set. */
        public Duration effectiveAdminIdleTimeout() {
            return adminIdleTimeout == null || adminIdleTimeout.isZero() ? idleTimeout : adminIdleTimeout;
        }

        public Duration getAbsoluteTimeout() {
            return absoluteTimeout;
        }

        public void setAbsoluteTimeout(Duration absoluteTimeout) {
            this.absoluteTimeout = absoluteTimeout;
        }

        public int getMaxPerUser() {
            return maxPerUser;
        }

        public void setMaxPerUser(int maxPerUser) {
            this.maxPerUser = Math.max(0, maxPerUser);
        }
    }
}

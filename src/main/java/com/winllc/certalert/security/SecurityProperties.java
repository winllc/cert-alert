package com.winllc.certalert.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Who may use this application, bound from {@code cert-alert.security}.
 *
 * <p>Two ways in. A client certificate is the primary one, because everybody in this
 * directory already has one and the application already knows which certificates the
 * directory publishes. A directory password is the fallback, for a browser that presents
 * no certificate.
 */
@ConfigurationProperties(prefix = "cert-alert.security")
public class SecurityProperties {

    /**
     * Turning this off leaves the application wide open. It exists for local development
     * against the sample directory, and is never appropriate anywhere else.
     */
    private boolean enabled = true;

    /**
     * Identifiers granted the admin role: any value the directory knows a person by - an
     * address, a uid, a common name. Everyone else who authenticates can read the tables
     * but cannot trigger jobs or reach the management endpoints.
     */
    private List<String> adminIdentifiers = new ArrayList<>();

    /**
     * Who may edit a server's points of contact.
     *
     * <p>Defaults to administrators, because a contact decides who hears that a certificate
     * is about to expire, and that is routing, not annotation. Set it to
     * {@code AUTHENTICATED} where the people who run the servers are expected to keep their
     * own contacts current.
     */
    private ContactEditors contactEditors = ContactEditors.ADMIN;

    private final X509 x509 = new X509();
    private final Ldap ldap = new Ldap();

    /** Who the write side of the contact endpoints is open to. */
    public enum ContactEditors {
        /** Only the configured administrators. */
        ADMIN,
        /** Anyone who has signed in. */
        AUTHENTICATED
    }

    public ContactEditors getContactEditors() {
        return contactEditors;
    }

    public void setContactEditors(ContactEditors contactEditors) {
        this.contactEditors = contactEditors;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getAdminIdentifiers() {
        return adminIdentifiers;
    }

    public void setAdminIdentifiers(List<String> adminIdentifiers) {
        this.adminIdentifiers = adminIdentifiers;
    }

    public X509 getX509() {
        return x509;
    }

    public Ldap getLdap() {
        return ldap;
    }

    /** Client certificate authentication. */
    public static class X509 {

        private boolean enabled = true;

        /**
         * Whether the presented certificate must be one the directory publishes.
         *
         * <p>On by default, and it is the point of the whole arrangement: the application
         * already caches every certificate in the directory, so a client certificate can be
         * recognised by its fingerprint rather than by pattern-matching a subject name.
         * Turning it off falls back to matching the certificate's subject and subject
         * alternative names against the people in the directory, which is weaker.
         */
        private boolean requireKnownCertificate = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isRequireKnownCertificate() {
            return requireKnownCertificate;
        }

        public void setRequireKnownCertificate(boolean requireKnownCertificate) {
            this.requireKnownCertificate = requireKnownCertificate;
        }
    }

    /** Password authentication, by binding to the directory as the user. */
    public static class Ldap {

        private boolean enabled = true;

        /** Search base for the login lookup, relative to {@code spring.ldap.base}. */
        private String userSearchBase = "";

        /** {@code {0}} is replaced with the submitted username. */
        private String userSearchFilter = "(uid={0})";

        /**
         * Bind straight to these DN patterns instead of searching first, when the directory
         * does not permit an anonymous or service-account search.
         */
        private List<String> userDnPatterns = new ArrayList<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUserSearchBase() {
            return userSearchBase;
        }

        public void setUserSearchBase(String userSearchBase) {
            this.userSearchBase = userSearchBase;
        }

        public String getUserSearchFilter() {
            return userSearchFilter;
        }

        public void setUserSearchFilter(String userSearchFilter) {
            this.userSearchFilter = userSearchFilter;
        }

        public List<String> getUserDnPatterns() {
            return userDnPatterns;
        }

        public void setUserDnPatterns(List<String> userDnPatterns) {
            this.userDnPatterns = userDnPatterns;
        }
    }
}

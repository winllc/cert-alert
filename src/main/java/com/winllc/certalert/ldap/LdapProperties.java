package com.winllc.certalert.ldap;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How to scrape the directory, bound from {@code cert-alert.ldap}.
 *
 * <p>Every attribute name is configurable rather than hard-coded. Directory profiles
 * disagree about what a server object is called and which attribute carries its point of
 * contact, so adapting to a specific schema should be a config change, not a code change.
 * The defaults follow inetOrgPerson (RFC 2798) for people and the device object class
 * (RFC 4519) for servers; confirm them against your directory before the first run.
 *
 * <p>Connection details (url, credentials, base) come from Spring's own {@code spring.ldap.*}
 * properties.
 */
@ConfigurationProperties(prefix = "cert-alert.ldap")
public class LdapProperties {

    /** Whether the scheduled sync runs. Sync can always be triggered through the API. */
    private boolean syncEnabled = true;

    /** Cron expression for the scheduled sync, evaluated in UTC. */
    private String syncCron = "0 0 */6 * * *";

    /** Page size for paged LDAP searches. */
    private int pageSize = 500;

    /**
     * Whether to use the paged results control. Directories usually cap how many entries a
     * single search may return, so paging is on by default; turn it off for servers that do
     * not support the control.
     */
    private boolean paged = true;

    /** Cap on entries returned per search. Zero means no limit. */
    private long countLimit = 0;

    /** Per-search time limit. */
    private Duration searchTimeout = Duration.ofSeconds(60);

    private final User user = new User();
    private final Server server = new Server();

    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    public void setSyncEnabled(boolean syncEnabled) {
        this.syncEnabled = syncEnabled;
    }

    public String getSyncCron() {
        return syncCron;
    }

    public void setSyncCron(String syncCron) {
        this.syncCron = syncCron;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public boolean isPaged() {
        return paged;
    }

    public void setPaged(boolean paged) {
        this.paged = paged;
    }

    public long getCountLimit() {
        return countLimit;
    }

    public void setCountLimit(long countLimit) {
        this.countLimit = countLimit;
    }

    public Duration getSearchTimeout() {
        return searchTimeout;
    }

    public void setSearchTimeout(Duration searchTimeout) {
        this.searchTimeout = searchTimeout;
    }

    public User getUser() {
        return user;
    }

    public Server getServer() {
        return server;
    }

    /** Where people live in the tree, and what their attributes are called. */
    public static class User {

        /** Search base, relative to {@code spring.ldap.base}. */
        private String searchBase = "";

        private String searchFilter = "(objectClass=inetOrgPerson)";

        private String uid = "uid";
        private String commonName = "cn";
        private String displayName = "displayName";
        private String givenName = "givenName";
        private String surname = "sn";
        private String email = "mail";
        private String telephoneNumber = "telephoneNumber";
        private String title = "title";
        private String employeeType = "employeeType";
        private String country = "c";
        private String organization = "o";
        private String organizationalUnit = "ou";
        private String certificate = "userCertificate";

        public String getSearchBase() {
            return searchBase;
        }

        public void setSearchBase(String searchBase) {
            this.searchBase = searchBase;
        }

        public String getSearchFilter() {
            return searchFilter;
        }

        public void setSearchFilter(String searchFilter) {
            this.searchFilter = searchFilter;
        }

        public String getUid() {
            return uid;
        }

        public void setUid(String uid) {
            this.uid = uid;
        }

        public String getCommonName() {
            return commonName;
        }

        public void setCommonName(String commonName) {
            this.commonName = commonName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getGivenName() {
            return givenName;
        }

        public void setGivenName(String givenName) {
            this.givenName = givenName;
        }

        public String getSurname() {
            return surname;
        }

        public void setSurname(String surname) {
            this.surname = surname;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getTelephoneNumber() {
            return telephoneNumber;
        }

        public void setTelephoneNumber(String telephoneNumber) {
            this.telephoneNumber = telephoneNumber;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getEmployeeType() {
            return employeeType;
        }

        public void setEmployeeType(String employeeType) {
            this.employeeType = employeeType;
        }

        public String getCountry() {
            return country;
        }

        public void setCountry(String country) {
            this.country = country;
        }

        public String getOrganization() {
            return organization;
        }

        public void setOrganization(String organization) {
            this.organization = organization;
        }

        public String getOrganizationalUnit() {
            return organizationalUnit;
        }

        public void setOrganizationalUnit(String organizationalUnit) {
            this.organizationalUnit = organizationalUnit;
        }

        public String getCertificate() {
            return certificate;
        }

        public void setCertificate(String certificate) {
            this.certificate = certificate;
        }
    }

    /** Where servers live in the tree, and what their attributes are called. */
    public static class Server {

        /** Search base, relative to {@code spring.ldap.base}. */
        private String searchBase = "";

        private String searchFilter = "(objectClass=device)";

        private String commonName = "cn";
        private String fqdn = "associatedDomain";
        private String description = "description";
        private String serialNumber = "serialNumber";
        private String operatingSystem = "operatingSystem";

        /** The point-of-contact attribute, joined to a user's email address. */
        private String serverPoc = "serverPoc";

        private String organization = "o";
        private String organizationalUnit = "ou";
        private String certificate = "userCertificate";

        public String getSearchBase() {
            return searchBase;
        }

        public void setSearchBase(String searchBase) {
            this.searchBase = searchBase;
        }

        public String getSearchFilter() {
            return searchFilter;
        }

        public void setSearchFilter(String searchFilter) {
            this.searchFilter = searchFilter;
        }

        public String getCommonName() {
            return commonName;
        }

        public void setCommonName(String commonName) {
            this.commonName = commonName;
        }

        public String getFqdn() {
            return fqdn;
        }

        public void setFqdn(String fqdn) {
            this.fqdn = fqdn;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getSerialNumber() {
            return serialNumber;
        }

        public void setSerialNumber(String serialNumber) {
            this.serialNumber = serialNumber;
        }

        public String getOperatingSystem() {
            return operatingSystem;
        }

        public void setOperatingSystem(String operatingSystem) {
            this.operatingSystem = operatingSystem;
        }

        public String getServerPoc() {
            return serverPoc;
        }

        public void setServerPoc(String serverPoc) {
            this.serverPoc = serverPoc;
        }

        public String getOrganization() {
            return organization;
        }

        public void setOrganization(String organization) {
            this.organization = organization;
        }

        public String getOrganizationalUnit() {
            return organizationalUnit;
        }

        public void setOrganizationalUnit(String organizationalUnit) {
            this.organizationalUnit = organizationalUnit;
        }

        public String getCertificate() {
            return certificate;
        }

        public void setCertificate(String certificate) {
            this.certificate = certificate;
        }
    }
}

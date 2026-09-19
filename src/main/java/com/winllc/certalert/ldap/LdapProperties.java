package com.winllc.certalert.ldap;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How to scrape the directory, bound from {@code cert-alert.ldap}.
 *
 * <p>Defaults follow the IC Full Service Directory schema (FSD v2021-NOV): IC Persons are
 * {@code icOrgPerson}, which derives from inetOrgPerson, and IC Non-Person Entities are
 * {@code icOrgServer}. Every attribute name is still configurable, because the
 * specification leaves the actual objectClass hierarchy to the implementing IC Element.
 *
 * <p>Connection details (url, credentials, base) come from Spring's own
 * {@code spring.ldap.*} properties.
 */
@ConfigurationProperties(prefix = "cert-alert.ldap")
public class LdapProperties {

    private final Sync sync = new Sync();
    private final Prune prune = new Prune();
    private final User user = new User();
    private final Server server = new Server();

    /** Page size for paged LDAP searches. */
    private int pageSize = 500;

    /**
     * Whether to use the paged results control. A directory of any size enforces an
     * administrative size limit, and an unpaged search simply stops there, silently
     * returning a partial view of the tree. Turn this off only for servers that do not
     * support the control.
     */
    private boolean paged = true;

    /**
     * How many entries are collected before being written in one transaction. Larger
     * batches mean fewer round trips; smaller ones keep the persistence context small.
     */
    private int batchSize = 200;

    /** Cap on entries returned per search. Zero means no limit. */
    private long countLimit = 0;

    /** Per-search time limit. */
    private Duration searchTimeout = Duration.ofMinutes(10);

    public Sync getSync() {
        return sync;
    }

    public Prune getPrune() {
        return prune;
    }

    public User getUser() {
        return user;
    }

    public Server getServer() {
        return server;
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

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
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

    /** Schedules for the scraping jobs. */
    public static class Sync {

        /** Whether the scheduled jobs run at all. Syncs can always be triggered via the API. */
        private boolean enabled = true;

        /** Cron for the user sweep, evaluated in UTC. */
        private String usersCron = "0 0 2 * * *";

        /** Cron for the server sweep, staggered so the two do not overlap. */
        private String serversCron = "0 0 4 * * *";

        /**
         * Cron for re-evaluating cached expiry. Certificates expire with time passing and
         * nothing changing in the directory, so this job re-reads no LDAP at all.
         */
        private String refreshCron = "0 15 * * * *";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUsersCron() {
            return usersCron;
        }

        public void setUsersCron(String usersCron) {
            this.usersCron = usersCron;
        }

        public String getServersCron() {
            return serversCron;
        }

        public void setServersCron(String serversCron) {
            this.serversCron = serversCron;
        }

        public String getRefreshCron() {
            return refreshCron;
        }

        public void setRefreshCron(String refreshCron) {
            this.refreshCron = refreshCron;
        }
    }

    /** Removal of entries the directory no longer publishes. */
    public static class Prune {

        /** Off by default: deleting directory records is not something to start doing silently. */
        private boolean enabled = false;

        /** Cron for the prune job, evaluated in UTC. */
        private String cron = "0 0 6 * * SUN";

        /**
         * An entry is pruned once it has gone unseen for this long. It wants to be
         * comfortably longer than the sync interval, so a single failed sweep never
         * deletes the directory.
         */
        private Duration after = Duration.ofDays(30);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public Duration getAfter() {
            return after;
        }

        public void setAfter(Duration after) {
            this.after = after;
        }
    }

    /** Where IC Persons live in the tree, and what their attributes are called. */
    public static class User {

        /** Search base, relative to {@code spring.ldap.base}. */
        private String searchBase = "";

        private String searchFilter = "(objectClass=icOrgPerson)";

        /**
         * Which address becomes the primary one shown in the tables, first match wins.
         * All of them are indexed for the point-of-contact join regardless.
         */
        private List<String> emailPrecedence = List.of("icEmail", "mail", "internetEmail", "siprnetEmail", "niprnetEmail");

        /** IC FSD: inetOrgPerson uid. */
        private String uid = "uid";

        /** IC FSD: person cn. */
        private String commonName = "cn";

        /** IC FSD: inetOrgPerson displayName. */
        private String displayName = "displayName";

        /** IC FSD: icOrgPerson preferredName. */
        private String preferredName = "preferredName";

        /** IC FSD: inetOrgPerson givenName. */
        private String givenName = "givenName";

        /** IC FSD: person sn. */
        private String surname = "sn";

        /** IC FSD: inetOrgPerson mail. */
        private String mail = "mail";

        /** IC FSD: icOrgPerson icEmail. */
        private String icEmail = "icEmail";

        /** IC FSD: icOrgPerson internetEmail. */
        private String internetEmail = "internetEmail";

        /** IC FSD: icOrgPerson niprnetEmail. */
        private String niprnetEmail = "niprnetEmail";

        /** IC FSD: icOrgPerson siprnetEmail. */
        private String siprnetEmail = "siprnetEmail";

        /** IC FSD: organizationalPerson telephoneNumber. */
        private String telephoneNumber = "telephoneNumber";

        /** IC FSD: organizationalPerson title. */
        private String title = "title";

        /** IC FSD: inetOrgPerson employeeType. */
        private String employeeType = "employeeType";

        /** IC FSD: icOrgPerson rank. */
        private String rank = "rank";

        /** IC FSD: icOrgPerson countryOfAffiliation (mandatory). */
        private String countryOfAffiliation = "countryOfAffiliation";

        /** IC FSD: icOrgPerson dutyOrganization (mandatory). */
        private String dutyOrganization = "dutyOrganization";

        /**
         * IC FSD: icOrgPerson dutySubOrganization. Optional, and the finer of the two
         * organizational answers - a duty organization is an agency, and this is the part
         * of it somebody actually works in.
         */
        private String dutySubOrganization = "dutySubOrganization";

        /** IC FSD: icOrgPerson adminOrganization (mandatory). */
        private String adminOrganization = "adminOrganization";

        /** IC FSD: icOrgPerson isICMember (mandatory). */
        private String icMember = "isICMember";

        /** IC FSD: icOrgPerson icNetworks (mandatory). */
        private String icNetworks = "icNetworks";

        /** IC FSD: icOrgPerson resourceSecurityMark (mandatory). */
        private String resourceSecurityMark = "resourceSecurityMark";

        /** IC FSD: organization. */
        private String organization = "o";

        /** IC FSD: organizationalUnit. */
        private String organizationalUnit = "ou";

        /** IC FSD: published certificate. */
        private String certificate = "userCertificate";

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

        public String getPreferredName() {
            return preferredName;
        }

        public void setPreferredName(String preferredName) {
            this.preferredName = preferredName;
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

        public String getMail() {
            return mail;
        }

        public void setMail(String mail) {
            this.mail = mail;
        }

        public String getIcEmail() {
            return icEmail;
        }

        public void setIcEmail(String icEmail) {
            this.icEmail = icEmail;
        }

        public String getInternetEmail() {
            return internetEmail;
        }

        public void setInternetEmail(String internetEmail) {
            this.internetEmail = internetEmail;
        }

        public String getNiprnetEmail() {
            return niprnetEmail;
        }

        public void setNiprnetEmail(String niprnetEmail) {
            this.niprnetEmail = niprnetEmail;
        }

        public String getSiprnetEmail() {
            return siprnetEmail;
        }

        public void setSiprnetEmail(String siprnetEmail) {
            this.siprnetEmail = siprnetEmail;
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

        public String getRank() {
            return rank;
        }

        public void setRank(String rank) {
            this.rank = rank;
        }

        public String getCountryOfAffiliation() {
            return countryOfAffiliation;
        }

        public void setCountryOfAffiliation(String countryOfAffiliation) {
            this.countryOfAffiliation = countryOfAffiliation;
        }

        public String getDutyOrganization() {
            return dutyOrganization;
        }

        public void setDutyOrganization(String dutyOrganization) {
            this.dutyOrganization = dutyOrganization;
        }

        public String getDutySubOrganization() {
            return dutySubOrganization;
        }

        public void setDutySubOrganization(String dutySubOrganization) {
            this.dutySubOrganization = dutySubOrganization;
        }

        public String getAdminOrganization() {
            return adminOrganization;
        }

        public void setAdminOrganization(String adminOrganization) {
            this.adminOrganization = adminOrganization;
        }

        public String getIcMember() {
            return icMember;
        }

        public void setIcMember(String icMember) {
            this.icMember = icMember;
        }

        public String getIcNetworks() {
            return icNetworks;
        }

        public void setIcNetworks(String icNetworks) {
            this.icNetworks = icNetworks;
        }

        public String getResourceSecurityMark() {
            return resourceSecurityMark;
        }

        public void setResourceSecurityMark(String resourceSecurityMark) {
            this.resourceSecurityMark = resourceSecurityMark;
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

        public List<String> getEmailPrecedence() {
            return emailPrecedence;
        }

        public void setEmailPrecedence(List<String> emailPrecedence) {
            this.emailPrecedence = emailPrecedence;
        }
    }

    /** Where IC Non-Person Entities live in the tree, and what their attributes are called. */
    public static class Server {

        /** Search base, relative to {@code spring.ldap.base}. */
        private String searchBase = "";

        private String searchFilter = "(objectClass=icOrgServer)";

        /** IC FSD: icOrgServer cn (mandatory). */
        private String commonName = "cn";

        /** IC FSD: icOrgServer uid (mandatory). */
        private String uid = "uid";

        /** IC FSD: icOrgServer givenName (mandatory). */
        private String givenName = "givenName";

        /** IC FSD: icOrgServer description. */
        private String description = "description";

        /** IC FSD: icOrgServer serverURL. */
        private String serverUrl = "serverURL";

        /** IC FSD: icOrgServer icServerAddress (IP address). */
        private String icServerAddress = "icServerAddress";

        /** IC FSD: icOrgServer ATOStatus (mandatory). */
        private String atoStatus = "ATOStatus";

        /** IC FSD: icOrgServer lifeCycleStatus (mandatory). */
        private String lifeCycleStatus = "lifeCycleStatus";

        /** IC FSD: icOrgServer employeeType (mandatory). */
        private String employeeType = "employeeType";

        /** IC FSD: icOrgServer countryOfAffiliation (mandatory). */
        private String countryOfAffiliation = "countryOfAffiliation";

        /** IC FSD: icOrgServer dutyOrganization (mandatory). */
        private String dutyOrganization = "dutyOrganization";

        /**
         * IC FSD: icOrgServer dutySubOrganization. Optional, and the finer of the two
         * organizational answers - a duty organization is an agency, and this is the part
         * of it somebody actually works in.
         */
        private String dutySubOrganization = "dutySubOrganization";

        /** IC FSD: icOrgServer adminOrganization (mandatory). */
        private String adminOrganization = "adminOrganization";

        /** IC FSD: icOrgServer isICMember (mandatory). */
        private String icMember = "isICMember";

        /** IC FSD: icOrgServer icNetworks (mandatory). */
        private String icNetworks = "icNetworks";

        /** IC FSD: icOrgServer resourceSecurityMark (mandatory). */
        private String resourceSecurityMark = "resourceSecurityMark";

        /** IC FSD: icOrgServer serverPOC, the point of contact (mandatory). */
        private String serverPoc = "serverPOC";

        /** IC FSD: organization. */
        private String organization = "o";

        /** IC FSD: organizationalUnit. */
        private String organizationalUnit = "ou";

        /** IC FSD: published certificate (mandatory). */
        private String certificate = "userCertificate";

        public String getCommonName() {
            return commonName;
        }

        public void setCommonName(String commonName) {
            this.commonName = commonName;
        }

        public String getUid() {
            return uid;
        }

        public void setUid(String uid) {
            this.uid = uid;
        }

        public String getGivenName() {
            return givenName;
        }

        public void setGivenName(String givenName) {
            this.givenName = givenName;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getServerUrl() {
            return serverUrl;
        }

        public void setServerUrl(String serverUrl) {
            this.serverUrl = serverUrl;
        }

        public String getIcServerAddress() {
            return icServerAddress;
        }

        public void setIcServerAddress(String icServerAddress) {
            this.icServerAddress = icServerAddress;
        }

        public String getAtoStatus() {
            return atoStatus;
        }

        public void setAtoStatus(String atoStatus) {
            this.atoStatus = atoStatus;
        }

        public String getLifeCycleStatus() {
            return lifeCycleStatus;
        }

        public void setLifeCycleStatus(String lifeCycleStatus) {
            this.lifeCycleStatus = lifeCycleStatus;
        }

        public String getEmployeeType() {
            return employeeType;
        }

        public void setEmployeeType(String employeeType) {
            this.employeeType = employeeType;
        }

        public String getCountryOfAffiliation() {
            return countryOfAffiliation;
        }

        public void setCountryOfAffiliation(String countryOfAffiliation) {
            this.countryOfAffiliation = countryOfAffiliation;
        }

        public String getDutyOrganization() {
            return dutyOrganization;
        }

        public void setDutyOrganization(String dutyOrganization) {
            this.dutyOrganization = dutyOrganization;
        }

        public String getDutySubOrganization() {
            return dutySubOrganization;
        }

        public void setDutySubOrganization(String dutySubOrganization) {
            this.dutySubOrganization = dutySubOrganization;
        }

        public String getAdminOrganization() {
            return adminOrganization;
        }

        public void setAdminOrganization(String adminOrganization) {
            this.adminOrganization = adminOrganization;
        }

        public String getIcMember() {
            return icMember;
        }

        public void setIcMember(String icMember) {
            this.icMember = icMember;
        }

        public String getIcNetworks() {
            return icNetworks;
        }

        public void setIcNetworks(String icNetworks) {
            this.icNetworks = icNetworks;
        }

        public String getResourceSecurityMark() {
            return resourceSecurityMark;
        }

        public void setResourceSecurityMark(String resourceSecurityMark) {
            this.resourceSecurityMark = resourceSecurityMark;
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
    }
}

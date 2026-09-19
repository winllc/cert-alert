package com.winllc.certalert.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.BatchSize;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * An IC Non-Person Entity ({@code icOrgServer}), with the certificates it publishes.
 *
 * <p>{@code serverPOC} is defined as the <em>name</em> of the person or organizational
 * point of contact responsible for the server, and as single-valued. It is held as a set
 * anyway: a name is not a reliable key, directories do deviate from the specification, and
 * tolerating several costs nothing. Whatever it holds - a name or an address - is matched
 * against {@link DirectoryUser#getIdentifiers()}, which carries both.
 */
@Entity
@Table(
        name = "directory_server",
        uniqueConstraints = @UniqueConstraint(name = "uk_directory_server_dn", columnNames = "dn"),
        indexes = {
            @Index(name = "idx_directory_server_cn", columnList = "common_name"),
            @Index(name = "idx_directory_server_url", columnList = "server_url"),
            @Index(name = "idx_directory_server_cert_status", columnList = "certificate_status"),
            @Index(name = "idx_directory_server_earliest_expiry", columnList = "earliest_expiry"),
            @Index(name = "idx_directory_server_last_synced", columnList = "last_synced_at")
        })
public class DirectoryServer extends DirectoryEntry {

    @Column(name = "common_name", length = 255)
    private String commonName;

    @Column(length = 255)
    private String uid;

    @Column(name = "given_name", length = 255)
    private String givenName;

    @Column(length = 1000)
    private String description;

    /** {@code serverURL}: the server's URL, where it has one. */
    @Column(name = "server_url", length = 500)
    private String serverUrl;

    /** {@code icServerAddress}: the server's IPv4 or IPv6 address. */
    @Column(name = "ic_server_address", length = 128)
    private String icServerAddress;

    @Column(name = "ato_status", length = 128)
    private String atoStatus;

    @Column(name = "life_cycle_status", length = 128)
    private String lifeCycleStatus;

    @Column(name = "employee_type", length = 128)
    private String employeeType;

    @Column(name = "country_of_affiliation", length = 128)
    private String countryOfAffiliation;

    @Column(name = "duty_organization", length = 255)
    private String dutyOrganization;

    /** The part of the duty organization this entry belongs to, where the directory says. */
    @Column(name = "duty_sub_organization", length = 255)
    private String dutySubOrganization;

    @Column(name = "admin_organization", length = 255)
    private String adminOrganization;

    @Column(name = "is_ic_member")
    private Boolean icMember;

    @Column(name = "ic_networks", length = 500)
    private String icNetworks;

    @Column(name = "resource_security_mark", length = 500)
    private String resourceSecurityMark;

    /** Points of contact, lowercased. Matched against {@code DirectoryUser.identifiers}. */
    @BatchSize(size = 200)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "directory_server_poc",
            joinColumns = @JoinColumn(
                    name = "server_id",
                    foreignKey = @ForeignKey(name = "fk_server_poc_server")),
            indexes = @Index(name = "idx_server_poc_value", columnList = "poc_value"))
    @Column(name = "poc_value", length = 320, nullable = false)
    private Set<String> serverPocs = new LinkedHashSet<>();

    /** The same contacts flattened for display and global search. */
    @Column(name = "server_poc_display", length = 2000)
    private String serverPocDisplay;

    @OneToMany(mappedBy = "server", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CachedCertificate> certificates = new ArrayList<>();

    /**
     * Points of contact added here rather than scraped. Kept apart from {@link #serverPocs}
     * precisely so a sweep, which replaces that set wholesale, cannot delete them.
     */
    @OneToMany(mappedBy = "server", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("addedAt, id")
    private List<ServerContact> managedContacts = new ArrayList<>();

    protected DirectoryServer() {
        // for JPA
    }

    public DirectoryServer(String dn) {
        super(dn);
    }

    @Override
    public List<CachedCertificate> getCertificates() {
        return certificates;
    }

    public void addCertificate(CachedCertificate certificate) {
        certificates.add(certificate);
        certificate.assignTo(this);
    }

    public void removeCertificate(CachedCertificate certificate) {
        certificates.remove(certificate);
    }

    public Set<String> getServerPocs() {
        return serverPocs;
    }

    public List<ServerContact> getManagedContacts() {
        return managedContacts;
    }

    public void addManagedContact(ServerContact contact) {
        managedContacts.add(contact);
    }

    public boolean removeManagedContact(ServerContact contact) {
        return managedContacts.remove(contact);
    }

    /** Replaces the contact list, normalising case and refreshing the display column. */
    public void setServerPocs(Collection<String> pocs) {
        Set<String> refreshed = new LinkedHashSet<>();
        if (pocs != null) {
            pocs.stream()
                    .filter(poc -> poc != null && !poc.isBlank())
                    .map(poc -> poc.trim().toLowerCase(Locale.ROOT))
                    .forEach(refreshed::add);
        }
        // Replace in place so Hibernate does not re-insert every row on every sync.
        if (!refreshed.equals(this.serverPocs)) {
            this.serverPocs.clear();
            this.serverPocs.addAll(refreshed);
        }
        this.serverPocDisplay = refreshed.isEmpty() ? null : String.join(", ", refreshed);
    }

    public String getServerPocDisplay() {
        return serverPocDisplay;
    }

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
        this.serverUrl = serverUrl == null ? null : serverUrl.trim();
    }

    public String getIcServerAddress() {
        return icServerAddress;
    }

    public void setIcServerAddress(String icServerAddress) {
        this.icServerAddress = icServerAddress == null ? null : icServerAddress.trim();
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

    public Boolean getIcMember() {
        return icMember;
    }

    public void setIcMember(Boolean icMember) {
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
}

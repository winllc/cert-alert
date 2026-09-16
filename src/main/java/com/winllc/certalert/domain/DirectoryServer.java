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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A server from the directory, with the certificates it publishes.
 *
 * <p>{@code serverPoc} is multi-valued in the directory, so the points of contact are held
 * as a collection and joined against {@link DirectoryUser#getEmail()} to answer "which
 * servers is this person responsible for". {@code serverPocDisplay} is the same list
 * flattened onto the row, purely so the search table can show and globally search the
 * contacts without a join.
 */
@Entity
@Table(
        name = "directory_server",
        uniqueConstraints = @UniqueConstraint(name = "uk_directory_server_dn", columnNames = "dn"),
        indexes = {
            @Index(name = "idx_directory_server_cn", columnList = "common_name"),
            @Index(name = "idx_directory_server_fqdn", columnList = "fqdn"),
            @Index(name = "idx_directory_server_cert_status", columnList = "certificate_status"),
            @Index(name = "idx_directory_server_earliest_expiry", columnList = "earliest_expiry")
        })
public class DirectoryServer extends DirectoryEntry {

    @Column(name = "common_name", length = 255)
    private String commonName;

    @Column(length = 255)
    private String fqdn;

    @Column(length = 1000)
    private String description;

    @Column(name = "serial_number", length = 128)
    private String serialNumber;

    @Column(name = "operating_system", length = 255)
    private String operatingSystem;

    /** Points of contact, by email address, lowercased. Joined to {@code DirectoryUser.email}. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "directory_server_poc",
            joinColumns = @JoinColumn(
                    name = "server_id",
                    foreignKey = @ForeignKey(name = "fk_server_poc_server")),
            indexes = @Index(name = "idx_server_poc_email", columnList = "poc_email"))
    @Column(name = "poc_email", length = 320, nullable = false)
    private Set<String> serverPocs = new LinkedHashSet<>();

    /** The same contacts flattened for display and global search. */
    @Column(name = "server_poc_display", length = 2000)
    private String serverPocDisplay;

    @OneToMany(mappedBy = "server", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CachedCertificate> certificates = new ArrayList<>();

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

    /** Replaces the contact list, normalising case and refreshing the display column. */
    public void setServerPocs(Collection<String> pocs) {
        this.serverPocs.clear();
        if (pocs != null) {
            pocs.stream()
                    .filter(poc -> poc != null && !poc.isBlank())
                    .map(poc -> poc.trim().toLowerCase(Locale.ROOT))
                    .forEach(this.serverPocs::add);
        }
        this.serverPocDisplay = this.serverPocs.isEmpty() ? null : String.join(", ", this.serverPocs);
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

    public String getFqdn() {
        return fqdn;
    }

    public void setFqdn(String fqdn) {
        this.fqdn = fqdn == null ? null : fqdn.trim().toLowerCase(Locale.ROOT);
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
}

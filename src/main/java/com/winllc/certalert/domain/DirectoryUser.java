package com.winllc.certalert.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.ArrayList;
import java.util.List;

/**
 * A person from the directory, with the certificates they publish.
 *
 * <p>{@code email} is the join key between people and the servers they are responsible
 * for: a server lists its points of contact by email in {@code serverPoc}. It is indexed
 * for that reason, and stored lowercased so the join and the searches do not depend on how
 * the directory happened to case the address.
 */
@Entity
@Table(
        name = "directory_user",
        uniqueConstraints = @UniqueConstraint(name = "uk_directory_user_dn", columnNames = "dn"),
        indexes = {
            @Index(name = "idx_directory_user_email", columnList = "email"),
            @Index(name = "idx_directory_user_uid", columnList = "uid"),
            @Index(name = "idx_directory_user_cert_status", columnList = "certificate_status"),
            @Index(name = "idx_directory_user_earliest_expiry", columnList = "earliest_expiry")
        })
public class DirectoryUser extends DirectoryEntry {

    @Column(length = 255)
    private String uid;

    @Column(name = "common_name", length = 255)
    private String commonName;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "given_name", length = 255)
    private String givenName;

    @Column(length = 255)
    private String surname;

    @Column(length = 320)
    private String email;

    @Column(name = "telephone_number", length = 64)
    private String telephoneNumber;

    @Column(length = 255)
    private String title;

    @Column(name = "employee_type", length = 128)
    private String employeeType;

    @Column(length = 128)
    private String country;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CachedCertificate> certificates = new ArrayList<>();

    protected DirectoryUser() {
        // for JPA
    }

    public DirectoryUser(String dn) {
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

    /** Stored lowercased so the serverPoc join never depends on directory casing. */
    public void setEmail(String email) {
        this.email = email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
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
}

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
import org.hibernate.annotations.BatchSize;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * An IC Person ({@code icOrgPerson}), with the certificates they publish.
 *
 * <p>The IC FSD schema gives a person up to four network-specific addresses on top of the
 * inetOrgPerson {@code mail} attribute, all single-valued: {@code icEmail},
 * {@code internetEmail}, {@code niprnetEmail} and {@code siprnetEmail}. They are kept
 * apart because they say different things about where a person is reachable, with
 * {@link #getEmail()} holding whichever the configured precedence picked as primary.
 *
 * <p>{@link #getIdentifiers()} is the set of values by which a server might name this
 * person in its {@code serverPOC} attribute - every address plus every form of their name.
 * That set is the join between people and the servers they are responsible for.
 */
@Entity
@Table(
        name = "directory_user",
        uniqueConstraints = @UniqueConstraint(name = "uk_directory_user_dn", columnNames = "dn"),
        indexes = {
            @Index(name = "idx_directory_user_email", columnList = "email"),
            @Index(name = "idx_directory_user_uid", columnList = "uid"),
            @Index(name = "idx_directory_user_cert_status", columnList = "certificate_status"),
            @Index(name = "idx_directory_user_earliest_expiry", columnList = "earliest_expiry"),
            @Index(name = "idx_directory_user_last_synced", columnList = "last_synced_at")
        })
public class DirectoryUser extends DirectoryEntry {

    @Column(length = 255)
    private String uid;

    @Column(name = "common_name", length = 255)
    private String commonName;

    @Column(name = "display_name", length = 255)
    private String displayName;

    @Column(name = "preferred_name", length = 255)
    private String preferredName;

    @Column(name = "given_name", length = 255)
    private String givenName;

    @Column(length = 255)
    private String surname;

    /** Primary address, resolved from the addresses below by the configured precedence. */
    @Column(length = 320)
    private String email;

    @Column(name = "mail", length = 320)
    private String mail;

    @Column(name = "ic_email", length = 320)
    private String icEmail;

    @Column(name = "internet_email", length = 320)
    private String internetEmail;

    @Column(name = "niprnet_email", length = 320)
    private String niprnetEmail;

    @Column(name = "siprnet_email", length = 320)
    private String siprnetEmail;

    @Column(name = "telephone_number", length = 64)
    private String telephoneNumber;

    @Column(length = 255)
    private String title;

    @Column(name = "employee_type", length = 128)
    private String employeeType;

    @Column(name = "rank_title", length = 128)
    private String rank;

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

    /**
     * Every value a {@code serverPOC} could use to name this person, lowercased. Held in
     * its own table so the join is an indexed lookup rather than a scan over columns.
     */
    @BatchSize(size = 200)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "directory_user_identifier",
            joinColumns = @JoinColumn(
                    name = "user_id",
                    foreignKey = @ForeignKey(name = "fk_user_identifier_user")),
            indexes = @Index(name = "idx_user_identifier_value", columnList = "identifier"))
    @Column(name = "identifier", length = 320, nullable = false)
    private Set<String> identifiers = new LinkedHashSet<>();

    /**
     * Addresses read out of the attributes a deployment named beyond the schema's five.
     *
     * <p>Held rather than folded straight into {@link #identifiers} because that set is
     * rebuilt from what this entry holds - by a sweep, and again whenever somebody edits
     * the addresses added here. Anything only the directory knows has to survive the second
     * of those, or an alias being added would quietly drop it until the next sweep.
     */
    @BatchSize(size = 200)
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "directory_user_email",
            joinColumns = @JoinColumn(
                    name = "user_id",
                    foreignKey = @ForeignKey(name = "fk_user_email_user")),
            indexes = @Index(name = "idx_user_email_value", columnList = "address"))
    @Column(name = "address", length = 320, nullable = false)
    private Set<String> additionalEmails = new LinkedHashSet<>();

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

    /**
     * Recomputes the set of values a server might use to name this person, from what the
     * directory publishes. Called after the directory attributes have been applied.
     *
     * @param primaryEmail the address chosen as primary by the configured precedence
     */
    public void refreshIdentifiers(String primaryEmail) {
        refreshIdentifiers(primaryEmail, java.util.List.of());
    }

    /**
     * The same, plus the addresses added here - an old address, a role address, a team's
     * list. A sweep rebuilds this set from the directory, so the extra addresses have to be
     * handed back in or they would last until the next one.
     *
     * @param primaryEmail the address chosen as primary by the configured precedence
     * @param aliases addresses from {@code user_email_alias}
     */
    public void refreshIdentifiers(String primaryEmail, Collection<String> aliases) {
        this.email = normalise(primaryEmail);
        Set<String> refreshed = new LinkedHashSet<>();
        Stream.of(email, mail, icEmail, internetEmail, niprnetEmail, siprnetEmail,
                        commonName, displayName, preferredName, uid)
                .map(DirectoryUser::normalise)
                .filter(java.util.Objects::nonNull)
                .forEach(refreshed::add);
        // The attributes this deployment named beyond the schema's five. Read from the
        // entry rather than passed in, so rebuilding after an alias edit keeps them.
        additionalEmails.stream()
                .map(DirectoryUser::normalise)
                .filter(java.util.Objects::nonNull)
                .forEach(refreshed::add);
        if (aliases != null) {
            aliases.stream()
                    .map(DirectoryUser::normalise)
                    .filter(java.util.Objects::nonNull)
                    .forEach(refreshed::add);
        }
        // Replace in place: Hibernate tracks this collection, and swapping the instance
        // would make it re-insert every row on every sync.
        if (!refreshed.equals(this.identifiers)) {
            this.identifiers.clear();
            this.identifiers.addAll(refreshed);
        }
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }

    public Set<String> getIdentifiers() {
        return identifiers;
    }

    /** Addresses from the extra attributes this deployment reads, lowercased. */
    public Set<String> getAdditionalEmails() {
        return additionalEmails;
    }

    /**
     * Replaces them, and says whether anything changed.
     *
     * <p>In place rather than by swapping the set: Hibernate tracks this collection, and a
     * new instance would delete and re-insert every row on every sweep of the directory.
     *
     * @return whether the set now holds something different, so a sweep can tell an entry
     *     that has changed from one it has merely looked at again
     */
    public boolean setAdditionalEmails(Collection<String> addresses) {
        Set<String> refreshed = new LinkedHashSet<>();
        if (addresses != null) {
            addresses.stream()
                    .map(DirectoryUser::normalise)
                    .filter(java.util.Objects::nonNull)
                    .forEach(refreshed::add);
        }
        if (refreshed.equals(this.additionalEmails)) {
            return false;
        }
        this.additionalEmails.clear();
        this.additionalEmails.addAll(refreshed);
        return true;
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

    /** The primary address; set by {@link #refreshIdentifiers(String)}. */
    public String getEmail() {
        return email;
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

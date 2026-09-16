package com.winllc.certalert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * State shared by every object scraped out of the directory.
 *
 * <p>The certificate roll-up columns ({@code certificateCount}, {@code certificateStatus},
 * {@code earliestExpiry}, {@code latestExpiry}) are denormalised on purpose. The search
 * tables sort and filter on them constantly, and keeping them on the row turns
 * "show me everyone with an expired certificate" into an indexed predicate on a single
 * table instead of a correlated subquery over the certificate history.
 */
@MappedSuperclass
public abstract class DirectoryEntry {

    /**
     * Sequence-backed rather than identity: with an identity column Hibernate has to round
     * trip for every insert and cannot batch them, which on a directory of this size is the
     * difference between a sync taking seconds and taking many minutes.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "directory_entry_seq")
    @SequenceGenerator(name = "directory_entry_seq", sequenceName = "directory_entry_seq", allocationSize = 50)
    private Long id;

    /** Distinguished name. The directory's own identifier, and our natural key. */
    @Column(nullable = false, length = 512)
    private String dn;

    @Column(length = 255)
    private String organization;

    @Column(name = "organizational_unit", length = 255)
    private String organizationalUnit;

    @Column(name = "certificate_count", nullable = false)
    private int certificateCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "certificate_status", nullable = false, length = 32)
    private CertificateStatus certificateStatus = CertificateStatus.NONE;

    /** notAfter of the certificate that expires first; drives "expires next" sorting. */
    @Column(name = "earliest_expiry")
    private Instant earliestExpiry;

    @Column(name = "latest_expiry")
    private Instant latestExpiry;

    @Column(name = "first_seen_at", nullable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "last_synced_at", nullable = false)
    private Instant lastSyncedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected DirectoryEntry() {
        // for JPA
    }

    protected DirectoryEntry(String dn) {
        this.dn = dn;
    }

    /** The certificates currently published by this entry in the directory. */
    public abstract List<CachedCertificate> getCertificates();

    /** Marks the entry as seen in a sync run. */
    public void markSynced(Instant syncedAt) {
        if (this.firstSeenAt == null) {
            this.firstSeenAt = syncedAt;
        }
        this.lastSyncedAt = syncedAt;
    }

    /** Recomputes the denormalised roll-up from the entry's current certificates. */
    public void refreshCertificateSummary() {
        Collection<CachedCertificate> certificates = getCertificates();
        this.certificateCount = certificates.size();
        this.certificateStatus =
                CertificateStatus.worstOf(certificates.stream().map(CachedCertificate::getStatus).toList());
        this.earliestExpiry = certificates.stream()
                .map(CachedCertificate::getNotAfter)
                .filter(java.util.Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(null);
        this.latestExpiry = certificates.stream()
                .map(CachedCertificate::getNotAfter)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
    }

    public Long getId() {
        return id;
    }

    public String getDn() {
        return dn;
    }

    public void setDn(String dn) {
        this.dn = dn;
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

    public int getCertificateCount() {
        return certificateCount;
    }

    public CertificateStatus getCertificateStatus() {
        return certificateStatus;
    }

    public Instant getEarliestExpiry() {
        return earliestExpiry;
    }

    public Instant getLatestExpiry() {
        return latestExpiry;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public long getVersion() {
        return version;
    }
}

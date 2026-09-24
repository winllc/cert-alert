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

    /**
     * Recomputes the denormalised roll-up.
     *
     * <p>From the certificates the entry still stands on, which is not the same as all of
     * them. A directory keeps what it was given: renewing publishes the new certificate
     * and does not withdraw the old one, so an entry that rotated exactly as it should goes
     * on publishing the one it replaced until somebody clears it out. Rolling the worst
     * state over everything reported that entry as EXPIRED while the certificate it is
     * actually serving was fine - so every correct renewal produced a false alarm, and the
     * entries that genuinely had expired were the hardest to pick out of them.
     *
     * <p>An expired certificate is therefore passed over, and the status and the two expiry
     * dates the tables sort on describe what is left. Which of what is left is
     * {@link #rollUpOver}'s to say, and the two object types answer differently: a person
     * needs every certificate still standing, because they hold a pair and both have to
     * work; a server needs the best one, because an endpoint presents one certificate and
     * the rest are leftovers.
     *
     * <p>When every certificate has expired they all count again, which is what keeps a
     * genuinely lapsed entry reading EXPIRED rather than as an entry with nothing.
     *
     * <p>{@code certificateCount} stays a count of everything published, because that is
     * what it is: the details page lists the superseded ones and they have not gone away.
     */
    public void refreshCertificateSummary() {
        List<CachedCertificate> certificates = getCertificates();
        this.certificateCount = certificates.size();
        List<CachedCertificate> counted = standingCertificates();

        // worstOf an empty list is NONE, which is the right answer for an entry that really
        // does hold no certificates - so that case needs no branch of its own.
        this.certificateStatus =
                CertificateStatus.worstOf(counted.stream().map(CachedCertificate::getStatus).toList());
        this.earliestExpiry = counted.stream()
                .map(CachedCertificate::getNotAfter)
                .filter(java.util.Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(null);
        this.latestExpiry = counted.stream()
                .map(CachedCertificate::getNotAfter)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
    }

    /**
     * The certificates this entry stands on - the ones the roll-up describes, and the ones
     * worth telling anybody about.
     *
     * <p>Everything that has not expired, narrowed by {@link #rollUpOver} to what the
     * object type actually depends on. Where nothing is left the entry has lapsed rather
     * than holding nothing, so they all count again and it reads as expired.
     */
    public List<CachedCertificate> standingCertificates() {
        List<CachedCertificate> certificates = getCertificates();
        List<CachedCertificate> standing = certificates.stream()
                .filter(certificate -> certificate.getStatus() != CertificateStatus.EXPIRED)
                .toList();
        return standing.isEmpty() ? certificates : rollUpOver(standing);
    }

    /**
     * Which of the certificates still standing the roll-up describes.
     *
     * <p>All of them, for a person: PKI for people issues two at once, a signing
     * certificate and a key encipherment one, and both have to work - so the worse of the
     * two is the state of the person, and one of them running out is worth reporting even
     * while the other is fine.
     *
     * <p>A server is not that shape, and {@link DirectoryServer} says so.
     */
    protected List<CachedCertificate> rollUpOver(List<CachedCertificate> standing) {
        return standing;
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

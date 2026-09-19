package com.winllc.certalert.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Certificate details cached from the directory, so searches and expiry reporting never
 * have to re-parse DER or go back to LDAP.
 *
 * <p>A certificate belongs to exactly one owner: either a user or a server. Both foreign
 * keys are nullable and a database check constraint enforces that precisely one is set.
 * Keeping both kinds in one table means a cross-cutting "every certificate expiring this
 * month" query stays a single scan.
 *
 * <p>Identity is the SHA-256 fingerprint of the DER encoding, which is what lets a sync
 * recognise a certificate it has already cached and leave it untouched.
 */
@Entity
@Table(
        name = "cached_certificate",
        indexes = {
            @Index(name = "idx_cached_certificate_user", columnList = "user_id"),
            @Index(name = "idx_cached_certificate_server", columnList = "server_id"),
            @Index(name = "idx_cached_certificate_not_after", columnList = "not_after"),
            @Index(name = "idx_cached_certificate_status", columnList = "status"),
            @Index(name = "idx_cached_certificate_fingerprint", columnList = "sha256_fingerprint"),
            // For reporting on what the directory is signing with: which entries are still
            // on a weak digest, and which on an undersized key.
            @Index(name = "idx_cached_certificate_hash_algorithm", columnList = "hash_algorithm"),
            @Index(name = "idx_cached_certificate_key", columnList = "key_algorithm, key_size")
        })
public class CachedCertificate {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "cached_certificate_seq")
    @SequenceGenerator(
            name = "cached_certificate_seq",
            sequenceName = "cached_certificate_seq",
            allocationSize = 100)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", foreignKey = @ForeignKey(name = "fk_cached_certificate_user"))
    private DirectoryUser user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "server_id", foreignKey = @ForeignKey(name = "fk_cached_certificate_server"))
    private DirectoryServer server;

    @Column(name = "sha256_fingerprint", nullable = false, length = 64)
    private String sha256Fingerprint;

    @Column(name = "serial_number", length = 100)
    private String serialNumber;

    @Column(name = "subject_dn", length = 1000)
    private String subjectDn;

    @Column(name = "issuer_dn", length = 1000)
    private String issuerDn;

    @Column(name = "not_before")
    private Instant notBefore;

    @Column(name = "not_after")
    private Instant notAfter;

    /** As the provider names it: {@code SHA256withRSA}. Two facts in one string. */
    @Column(name = "signature_algorithm", length = 100)
    private String signatureAlgorithm;

    /**
     * The digest half of that, on its own and canonically spelled - {@code SHA-256} - so a
     * report can group and filter on it without pattern-matching the name. Null where the
     * scheme has no separate digest to name, which is the Ed25519 and Ed448 case.
     */
    @Column(name = "hash_algorithm", length = 32)
    private String hashAlgorithm;

    /** The public key's algorithm: {@code RSA}, {@code EC}, {@code DSA}. */
    @Column(name = "key_algorithm", length = 50)
    private String keyAlgorithm;

    /** Its size in bits: the modulus for RSA and DSA, the field size for EC. */
    @Column(name = "key_size")
    private Integer keySize;

    @Column(name = "subject_alternative_names", length = 2000)
    private String subjectAlternativeNames;

    /**
     * How many names the certificate carries, which is not how many are stored: the list is
     * truncated when it is long, and the count is the honest one from the certificate.
     */
    @Column(name = "san_count")
    private Integer subjectAltNameCount;

    /**
     * What is worrying about those names, as {@link CertificateRisk} names separated by
     * commas, or null where nothing is. A string rather than a table because it is read with
     * the row every time and written once, and because "is there anything" - the question
     * the tables ask - is then a null check.
     */
    @Column(name = "risk_flags", length = 200)
    private String riskFlags;

    /**
     * The key usage extension, as {@link KeyUsage} names separated by commas, or null where
     * the certificate carries no such extension. Stored the same way as the risk flags and
     * for the same reason: read with the row, written once, and the question asked of it -
     * is this the signing one or the encryption one - is answered from the string.
     */
    @Column(name = "key_usage", length = 200)
    private String keyUsage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private CertificateStatus status = CertificateStatus.VALID;

    @Column(name = "cached_at", nullable = false)
    private Instant cachedAt;

    protected CachedCertificate() {
        // for JPA
    }

    public CachedCertificate(
            String sha256Fingerprint,
            String serialNumber,
            String subjectDn,
            String issuerDn,
            Instant notBefore,
            Instant notAfter,
            String signatureAlgorithm,
            String hashAlgorithm,
            String keyAlgorithm,
            Integer keySize,
            String subjectAlternativeNames,
            Instant cachedAt) {
        this.sha256Fingerprint = sha256Fingerprint;
        this.serialNumber = serialNumber;
        this.subjectDn = subjectDn;
        this.issuerDn = issuerDn;
        this.notBefore = notBefore;
        this.notAfter = notAfter;
        this.signatureAlgorithm = signatureAlgorithm;
        this.hashAlgorithm = hashAlgorithm;
        this.keyAlgorithm = keyAlgorithm;
        this.keySize = keySize;
        this.subjectAlternativeNames = subjectAlternativeNames;
        this.cachedAt = cachedAt;
    }

    void assignTo(DirectoryUser owner) {
        this.user = owner;
        this.server = null;
    }

    void assignTo(DirectoryServer owner) {
        this.server = owner;
        this.user = null;
    }

    /** Refreshes the cached validity state; the status is stored so searches can index it. */
    public void updateStatus(CertificateStatus status, Instant evaluatedAt) {
        this.status = status;
        this.cachedAt = evaluatedAt;
    }

    public Long getId() {
        return id;
    }

    public DirectoryUser getUser() {
        return user;
    }

    public DirectoryServer getServer() {
        return server;
    }

    public String getSha256Fingerprint() {
        return sha256Fingerprint;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public String getSubjectDn() {
        return subjectDn;
    }

    public String getIssuerDn() {
        return issuerDn;
    }

    public Instant getNotBefore() {
        return notBefore;
    }

    public Instant getNotAfter() {
        return notAfter;
    }

    public String getSignatureAlgorithm() {
        return signatureAlgorithm;
    }

    public String getHashAlgorithm() {
        return hashAlgorithm;
    }

    public String getKeyAlgorithm() {
        return keyAlgorithm;
    }

    public Integer getKeySize() {
        return keySize;
    }

    public String getSubjectAlternativeNames() {
        return subjectAlternativeNames;
    }

    /**
     * Records what the names are and what is worrying about them. Called with the
     * certificate in hand, because the stored list may be truncated and the count may not.
     */
    public void describeNames(int count, Collection<CertificateRisk> risks) {
        this.subjectAltNameCount = count;
        this.riskFlags = risks == null || risks.isEmpty()
                ? null
                : risks.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    public Integer getSubjectAltNameCount() {
        return subjectAltNameCount;
    }

    /** What is worrying about the names, or empty where nothing is. */
    public Set<CertificateRisk> getRisks() {
        return flags(riskFlags, CertificateRisk.class);
    }

    /**
     * Reads one of the comma-separated flag columns.
     *
     * <p>A name this version does not know is dropped rather than thrown on: a row written
     * by a later version of the application, read by an older one during a rolling deploy,
     * is a thing that happens and is not worth a failed page for.
     */
    private static <E extends Enum<E>> Set<E> flags(String stored, Class<E> type) {
        if (stored == null || stored.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(stored.split(","))
                .map(String::trim)
                .filter(flag -> !flag.isEmpty())
                .map(flag -> {
                    try {
                        return Enum.valueOf(type, flag);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(type)));
    }

    public boolean isRisky() {
        return riskFlags != null && !riskFlags.isBlank();
    }

    /** Records what the key is allowed to do. Called with the certificate in hand. */
    public void describeKeyUsage(Collection<KeyUsage> usages) {
        this.keyUsage = usages == null || usages.isEmpty()
                ? null
                : usages.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    /** What the key is allowed to do, or empty where the certificate does not say. */
    public Set<KeyUsage> getKeyUsages() {
        return flags(keyUsage, KeyUsage.class);
    }

    /**
     * What the certificate is for: the signing half of a person's credentials, the
     * encryption half, or - as a server's usually is - both.
     */
    public CertificateUse getUse() {
        return CertificateUse.from(getKeyUsages());
    }

    public CertificateStatus getStatus() {
        return status;
    }

    public Instant getCachedAt() {
        return cachedAt;
    }
}

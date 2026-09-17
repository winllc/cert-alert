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

    public CertificateStatus getStatus() {
        return status;
    }

    public Instant getCachedAt() {
        return cachedAt;
    }
}

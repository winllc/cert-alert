package com.winllc.certalert.repository;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.RevocationStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CachedCertificateRepository extends JpaRepository<CachedCertificate, Long> {

    /**
     * Certificates whose cached status may have gone stale simply because time passed:
     * anything not already expired whose notAfter has come inside the warning window.
     * Ordered by id so the re-evaluation job can walk the whole set in stable pages.
     */
    @Query("""
            select c from CachedCertificate c
            where c.status <> :expired
              and c.notAfter is not null
              and c.notAfter <= :horizon
              and c.id > :afterId
            order by c.id
            """)
    List<CachedCertificate> findStale(
            @Param("expired") CertificateStatus expired,
            @Param("horizon") Instant horizon,
            @Param("afterId") long afterId,
            Pageable pageable);

    /**
     * Looks a certificate up by its fingerprint, with the owning person attached. This is
     * how a presented client certificate is recognised as one the directory publishes.
     */
    @Query("select c from CachedCertificate c left join fetch c.user where c.sha256Fingerprint = :fingerprint")
    List<CachedCertificate> findByFingerprint(@Param("fingerprint") String fingerprint);

    /**
     * What the digest reports: everything expiring between the floor and the horizon, owner
     * attached so the round-up can name what it is about.
     *
     * <p>Dates rather than cached statuses, because how far ahead the round-up looks is set
     * from the UI and may be further than the window a certificate is marked EXPIRING_SOON
     * in. Asking for status would then quietly report nothing beyond that window, whatever
     * the setting said. The floor is what leaves already-expired certificates out: pass the
     * current instant to exclude them, and something before any certificate to keep them.
     */
    @Query("""
            select c from CachedCertificate c
            left join fetch c.user
            left join fetch c.server
            where c.notAfter is not null
              and c.notAfter <= :horizon
              and c.notAfter > :floor
            order by c.notAfter
            """)
    List<CachedCertificate> findExpiringBetween(
            @Param("floor") Instant floor, @Param("horizon") Instant horizon, Pageable pageable);

    long countByStatus(CertificateStatus status);

    @Query("select distinct c.user.id from CachedCertificate c where c.id in :ids and c.user is not null")
    List<Long> findUserIdsByCertificateIds(@Param("ids") Collection<Long> ids);

    @Query("select distinct c.server.id from CachedCertificate c where c.id in :ids and c.server is not null")
    List<Long> findServerIdsByCertificateIds(@Param("ids") Collection<Long> ids);

    // --- walking the cache one owner at a time -------------------------------------------
    //
    // The revocation check is a question about an entry, not about a certificate: which of
    // a person's two are current can only be decided with both of them in view. So it walks
    // owners rather than rows, in id order, which is a keyset scan rather than an offset
    // that gets slower the further in it gets.

    @Query("select distinct c.user.id from CachedCertificate c where c.user.id > :after order by c.user.id")
    List<Long> findUserIdsAfter(@Param("after") long after, Pageable pageable);

    @Query("select distinct c.server.id from CachedCertificate c where c.server.id > :after order by c.server.id")
    List<Long> findServerIdsAfter(@Param("after") long after, Pageable pageable);

    @Query("select c from CachedCertificate c join fetch c.user where c.user.id in :ids")
    List<CachedCertificate> findForUsers(@Param("ids") Collection<Long> ids);

    @Query("select c from CachedCertificate c join fetch c.server where c.server.id in :ids")
    List<CachedCertificate> findForServers(@Param("ids") Collection<Long> ids);

    long countByRevocationStatus(RevocationStatus status);

    // --- what the directory should not still be publishing ---------------------------------

    /**
     * Certificates whose validity ran out before this. Ordered by expiry, oldest first, so
     * a capped run works through the most finished-with ones rather than an arbitrary
     * slice of them.
     *
     * <p>The owner's id comes back with the row rather than being reached for afterwards:
     * the association is lazy, and the job that reads these groups them by entry outside
     * any transaction.
     */
    @Query("select c.id as id, c.user.id as userId, c.server.id as serverId from CachedCertificate c "
            + "where c.notAfter is not null and c.notAfter < :before order by c.notAfter, c.id")
    List<CleanupCandidate> findExpiredBefore(@Param("before") Instant before, Pageable pageable);

    /** Certificates an authority has revoked, found out about before this. */
    @Query("select c.id as id, c.user.id as userId, c.server.id as serverId from CachedCertificate c "
            + "where c.revocationStatus = :status "
            + "and c.revocationCheckedAt is not null and c.revocationCheckedAt < :before "
            + "order by c.revocationCheckedAt, c.id")
    List<CleanupCandidate> findRevokedBefore(
            @Param("status") RevocationStatus status, @Param("before") Instant before, Pageable pageable);

    /** A certificate to remove, and the entry that publishes it. */
    interface CleanupCandidate {
        Long getId();

        Long getUserId();

        Long getServerId();
    }

    @Query("select count(c) from CachedCertificate c where c.notAfter is not null and c.notAfter < :before")
    long countExpiredBefore(@Param("before") Instant before);

    @Query("select count(c) from CachedCertificate c where c.revocationStatus = :status "
            + "and c.revocationCheckedAt is not null and c.revocationCheckedAt < :before")
    long countRevokedBefore(@Param("status") RevocationStatus status, @Param("before") Instant before);
}

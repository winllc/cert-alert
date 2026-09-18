package com.winllc.certalert.repository;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
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
}

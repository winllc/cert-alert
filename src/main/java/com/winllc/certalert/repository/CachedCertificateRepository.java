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
     * What the digest reports: everything in one of these states that expires before the
     * horizon, owner attached so the round-up can name what it is about. Already-expired
     * certificates come along with it - their notAfter is in the past, which is before any
     * horizon - unless the caller leaves EXPIRED out of the statuses.
     */
    @Query("""
            select c from CachedCertificate c
            left join fetch c.user
            left join fetch c.server
            where c.status in :statuses
              and c.notAfter is not null
              and c.notAfter <= :horizon
            order by c.notAfter
            """)
    List<CachedCertificate> findExpiringBefore(
            @Param("statuses") Collection<CertificateStatus> statuses,
            @Param("horizon") Instant horizon,
            Pageable pageable);

    long countByStatus(CertificateStatus status);

    @Query("select distinct c.user.id from CachedCertificate c where c.id in :ids and c.user is not null")
    List<Long> findUserIdsByCertificateIds(@Param("ids") Collection<Long> ids);

    @Query("select distinct c.server.id from CachedCertificate c where c.id in :ids and c.server is not null")
    List<Long> findServerIdsByCertificateIds(@Param("ids") Collection<Long> ids);
}

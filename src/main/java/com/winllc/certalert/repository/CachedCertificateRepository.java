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
     * Whether {@code r}, a certificate of the same entry, renews {@code c}. Kept apart from
     * the owner, so that each subquery of {@link #REPLACED} looks its candidates up by one indexed column:
     * one subquery matching either owner made H2 read every certificate for every row.
     */
    String RENEWAL_OF_C = """
            r.id <> c.id
                  and r.status <> :expired
                  and c.notBefore is not null and r.notBefore is not null and r.notBefore > c.notBefore
                  and c.subjectDn is not null and trim(c.subjectDn) <> ''
                  and lower(trim(r.subjectDn)) = lower(trim(c.subjectDn))
                  and (r.keyUsage = c.keyUsage or (r.keyUsage is null and c.keyUsage is null))
            """;

    /**
     * The renewal rule of {@code service.Renewals}, as a condition on {@code c}: the entry
     * holds another certificate, not expired, with the same subject and the same key usage,
     * issued later. Such a certificate has been dealt with, and the round-up leaves it out
     * in the query, so it reads no page and uses none of the limit.
     *
     * <p>Narrower than the Java rule, never wider - the same key usage implies the same use,
     * not the other way round - so what this drops the round-up would have dropped anyway,
     * and the Java rule still runs on what is left.
     */
    String REPLACED = """
            ((c.user is not null and exists (
                select r.id from CachedCertificate r
                where r.user = c.user
                  and\s""" + RENEWAL_OF_C + """
                ))
             or (c.server is not null and exists (
                select r.id from CachedCertificate r
                where r.server = c.server
                  and\s""" + RENEWAL_OF_C + """
                )))
            """;

    /**
     * One page of what the digest reports as about to expire: notAfter after the floor -
     * the present, when the digest asks - and by the horizon, soonest first, owner attached
     * so the round-up can name what it is about. Paged by key: the next page starts after
     * the last certificate of this one ({@code afterNotAfter}, {@code afterId}), so a page
     * costs the same however deep into the read it is.
     *
     * <p>Dates rather than cached statuses, because how far ahead the round-up looks is set
     * from the UI and may be further than the window a certificate is marked EXPIRING_SOON
     * in. Asking for status would then quietly report nothing beyond that window, whatever
     * the setting said. What has already expired is {@link #findLapsedPage}'s, read on its
     * own so that it cannot take this read's place. A certificate already renewed is left
     * out (see {@link #REPLACED}).
     */
    @Query("""
            select c from CachedCertificate c
            left join fetch c.user
            left join fetch c.server
            where c.notAfter is not null
              and c.notAfter <= :horizon
              and c.notAfter > :floor
              and (c.notAfter > :afterNotAfter or (c.notAfter = :afterNotAfter and c.id > :afterId))
              and not\s""" + REPLACED + """
            order by c.notAfter, c.id
            """)
    List<CachedCertificate> findExpiringPage(
            @Param("floor") Instant floor,
            @Param("horizon") Instant horizon,
            @Param("afterNotAfter") Instant afterNotAfter,
            @Param("afterId") long afterId,
            @Param("expired") CertificateStatus expired,
            Pageable pageable);

    /** How many {@link #findExpiringPage} leaves out as already renewed, for the run's account of itself. */
    @Query("""
            select count(c) from CachedCertificate c
            where c.notAfter is not null
              and c.notAfter <= :horizon
              and c.notAfter > :floor
              and\s""" + REPLACED)
    long countReplacedExpiringBetween(
            @Param("floor") Instant floor,
            @Param("horizon") Instant horizon,
            @Param("expired") CertificateStatus expired);

    /**
     * One page of what the digest reports as already expired: notAfter between the floor -
     * the end of the look-back - and {@code before}, owner attached, most recently expired
     * first, so a capped read keeps the ones somebody is likeliest still to be relying on.
     * Paged by key, as {@link #findExpiringPage} is, going backwards from
     * ({@code beforeNotAfter}, {@code beforeId}).
     *
     * <p>Without the ones the entry has plainly moved on from. A directory keeps publishing
     * a certificate after it has been renewed, so in a directory of any age most expired
     * certificates are leftovers beside a current one, and reading them all only to drop
     * them after would spend the read on nothing. The rule is the first half of
     * {@link com.winllc.certalert.domain.DirectoryEntry#standingCertificates()}: a
     * certificate marked expired is passed over when its entry holds another that is not -
     * and, for one not yet marked, {@link #REPLACED}. The rest of that question - which of
     * several a server stands on - is the caller's, because it compares subjects and names.
     */
    @Query("""
            select c from CachedCertificate c
            left join fetch c.user
            left join fetch c.server
            where c.notAfter is not null
              and c.notAfter <= :before
              and c.notAfter > :floor
              and (c.notAfter < :beforeNotAfter or (c.notAfter = :beforeNotAfter and c.id < :beforeId))
              and (c.status <> :expired
                   or ((c.user is null or not exists (
                            select o.id from CachedCertificate o
                            where o.user = c.user and o.id <> c.id and o.status <> :expired))
                       and (c.server is null or not exists (
                            select o.id from CachedCertificate o
                            where o.server = c.server and o.id <> c.id and o.status <> :expired))))
              and not\s""" + REPLACED + """
            order by c.notAfter desc, c.id desc
            """)
    List<CachedCertificate> findLapsedPage(
            @Param("floor") Instant floor,
            @Param("before") Instant before,
            @Param("beforeNotAfter") Instant beforeNotAfter,
            @Param("beforeId") long beforeId,
            @Param("expired") CertificateStatus expired,
            Pageable pageable);

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

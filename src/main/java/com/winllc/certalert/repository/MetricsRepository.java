package com.winllc.certalert.repository;

import com.winllc.certalert.domain.CachedCertificate;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * The aggregates behind the metrics page.
 *
 * <p>All of them are grouped counts done by the database. Reporting on a hundred thousand
 * certificates by pulling them into the application and counting them there would work
 * exactly once, on a small directory.
 *
 * <p>Dates are bucketed with {@code extract}, which is JPA's own and so says the same thing
 * to PostgreSQL and to H2. Truncating in the application would mean reading every row.
 */
public interface MetricsRepository extends JpaRepository<CachedCertificate, Long> {

    /** What the directory is signing with: one row per algorithm and size. */
    @Query("""
            select c.keyAlgorithm as algorithm, c.keySize as size, count(c) as total
            from CachedCertificate c
            group by c.keyAlgorithm, c.keySize
            order by count(c) desc
            """)
    List<KeyCount> countByKey();

    /** Which digests are still in use, the weak ones included. */
    @Query("""
            select c.hashAlgorithm as algorithm, count(c) as total
            from CachedCertificate c
            group by c.hashAlgorithm
            order by count(c) desc
            """)
    List<AlgorithmCount> countByHashAlgorithm();

    @Query("""
            select c.signatureAlgorithm as algorithm, count(c) as total
            from CachedCertificate c
            group by c.signatureAlgorithm
            order by count(c) desc
            """)
    List<AlgorithmCount> countBySignatureAlgorithm();

    /** When certificates were issued, by month. */
    @Query("""
            select extract(year from c.notBefore) as year, extract(month from c.notBefore) as month,
                   count(c) as total
            from CachedCertificate c
            where c.notBefore >= :from and c.notBefore < :to
            group by extract(year from c.notBefore), extract(month from c.notBefore)
            """)
    List<MonthCount> countIssuedByMonth(@Param("from") Instant from, @Param("to") Instant to);

    /** When they run out, by month - in the past, which is expiries, and ahead, which is work. */
    @Query("""
            select extract(year from c.notAfter) as year, extract(month from c.notAfter) as month,
                   count(c) as total
            from CachedCertificate c
            where c.notAfter >= :from and c.notAfter < :to
            group by extract(year from c.notAfter), extract(month from c.notAfter)
            """)
    List<MonthCount> countExpiringByMonth(@Param("from") Instant from, @Param("to") Instant to);

    @Query("select count(c) from CachedCertificate c where c.notAfter >= :from and c.notAfter < :to")
    long countExpiringBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** One algorithm and how many certificates use it. */
    /**
     * How many certificates carry each flag. Counted with a like per flag rather than by
     * grouping, because the column holds a set: one certificate can be two kinds of risky
     * and should be counted under both.
     */
    @Query("select count(c) from CachedCertificate c where c.riskFlags like concat('%', :flag, '%')")
    long countByRisk(@Param("flag") String flag);

    long countByRiskFlagsIsNotNull();

    // --- issuance -------------------------------------------------------------------------

    @Query("select count(c) from CachedCertificate c where c.notBefore >= :from and c.notBefore < :to")
    long countIssuedBetween(@Param("from") Instant from, @Param("to") Instant to);

    /**
     * Who issued what. The subject of a certificate says whose it is; the issuer says which
     * authority stands behind it, and on a directory this size the answer is usually a
     * handful of names plus a long tail nobody knew was there.
     */
    @Query("""
            select c.issuerDn as name, count(c) as total
            from CachedCertificate c
            where c.issuerDn is not null
            group by c.issuerDn
            order by count(c) desc
            """)
    List<NameCount> countByIssuer(Pageable pageable);

    /**
     * How long certificates are being issued for, in days, as an average over what was
     * issued recently. A policy change - three years down to ninety days - shows up here
     * before it shows up anywhere else.
     */
    @Query("""
            select avg(cast(extract(epoch from c.notAfter) as double)
                       - cast(extract(epoch from c.notBefore) as double))
            from CachedCertificate c
            where c.notBefore >= :from and c.notBefore is not null and c.notAfter is not null
            """)
    Double averageValiditySeconds(@Param("from") Instant from);

    // --- revocation -----------------------------------------------------------------------

    @Query("select count(c) from CachedCertificate c where c.revokedAt >= :from and c.revokedAt < :to")
    long countRevokedBetween(@Param("from") Instant from, @Param("to") Instant to);

    /** Why the authorities said they revoked them, which is the interesting half. */
    @Query("""
            select c.revocationReason as name, count(c) as total
            from CachedCertificate c
            where c.revocationReason is not null
            group by c.revocationReason
            order by count(c) desc
            """)
    List<NameCount> countByRevocationReason();

    /** When the oldest answer here was obtained, which is how stale the check is. */
    @Query("select min(c.revocationCheckedAt) from CachedCertificate c where c.revocationCheckedAt is not null")
    Instant oldestRevocationCheck();

    @Query("select max(c.revocationCheckedAt) from CachedCertificate c")
    Instant newestRevocationCheck();

    // --- the directory, grouped by what it says about itself ---------------------------------
    //
    // Counted over entries rather than certificates: the question "which office has the most
    // certificates" and "which office has the most entries" are different questions, and the
    // second is the one that says where the work is.

    @Query("""
            select coalesce(u.dutyOrganization, '') as name, count(u) as total
            from DirectoryUser u
            group by coalesce(u.dutyOrganization, '')
            order by count(u) desc
            """)
    List<NameCount> countUsersByDutyOrganization(Pageable pageable);

    @Query("""
            select coalesce(u.dutySubOrganization, '') as name, count(u) as total
            from DirectoryUser u
            group by coalesce(u.dutySubOrganization, '')
            order by count(u) desc
            """)
    List<NameCount> countUsersByDutySubOrganization(Pageable pageable);

    @Query("""
            select coalesce(u.employeeType, '') as name, count(u) as total
            from DirectoryUser u
            group by coalesce(u.employeeType, '')
            order by count(u) desc
            """)
    List<NameCount> countUsersByEmployeeType(Pageable pageable);

    @Query("""
            select coalesce(s.dutyOrganization, '') as name, count(s) as total
            from DirectoryServer s
            group by coalesce(s.dutyOrganization, '')
            order by count(s) desc
            """)
    List<NameCount> countServersByDutyOrganization(Pageable pageable);

    @Query("""
            select coalesce(s.dutySubOrganization, '') as name, count(s) as total
            from DirectoryServer s
            group by coalesce(s.dutySubOrganization, '')
            order by count(s) desc
            """)
    List<NameCount> countServersByDutySubOrganization(Pageable pageable);

    @Query("""
            select coalesce(s.employeeType, '') as name, count(s) as total
            from DirectoryServer s
            group by coalesce(s.employeeType, '')
            order by count(s) desc
            """)
    List<NameCount> countServersByEmployeeType(Pageable pageable);

    /** One value of something, and how many carry it. */
    interface NameCount {
        String getName();

        long getTotal();
    }

    interface AlgorithmCount {
        String getAlgorithm();

        long getTotal();
    }

    /** One key algorithm and size, and how many certificates use it. */
    interface KeyCount {
        String getAlgorithm();

        Integer getSize();

        long getTotal();
    }

    /** One month, and how many fell in it. */
    interface MonthCount {
        int getYear();

        int getMonth();

        long getTotal();
    }
}

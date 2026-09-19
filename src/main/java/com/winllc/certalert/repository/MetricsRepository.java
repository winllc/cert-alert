package com.winllc.certalert.repository;

import com.winllc.certalert.domain.CachedCertificate;
import java.time.Instant;
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

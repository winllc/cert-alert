package com.winllc.certalert.repository;

import com.winllc.certalert.domain.DirectoryServer;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.datatables.repository.DataTablesRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * IC Non-Person Entities, exposed to the search tables through {@link DataTablesRepository}.
 *
 * <p>The two collections are fetched by separate methods on purpose. Joining both in one
 * query multiplies the rows together, and a {@code List} of certificates would come back
 * holding duplicates.
 */
public interface DirectoryServerRepository extends DataTablesRepository<DirectoryServer, Long> {

    Optional<DirectoryServer> findByDn(String dn);

    @EntityGraph(attributePaths = "certificates")
    List<DirectoryServer> findAllByDnIn(Collection<String> dns);

    @EntityGraph(attributePaths = "certificates")
    Optional<DirectoryServer> findWithCertificatesById(Long id);

    @EntityGraph(attributePaths = "serverPocs")
    Optional<DirectoryServer> findWithPocsById(Long id);

    /**
     * What the next prune would delete, named well enough to audit. Read before the bulk
     * delete, because afterwards there is nothing left to name.
     */
    @Query("select s.id as id, s.dn as dn, coalesce(s.commonName, s.uid) as name from DirectoryServer s "
            + "where s.lastSyncedAt < :cutoff")
    List<PrunableEntry> findPrunableBefore(@Param("cutoff") Instant cutoff);

    /**
     * Servers whose name or URL contains this text, for a picker. Bounded by the page size
     * and by how much somebody types, like the one for people.
     */
    @Query("select s from DirectoryServer s where lower(s.commonName) like concat('%', :term, '%') "
            + "or lower(s.serverUrl) like concat('%', :term, '%') order by s.commonName")
    List<DirectoryServer> searchByName(@Param("term") String term, Pageable pageable);

    long countByCertificateCount(int certificateCount);

    long countByLastSyncedAtBefore(Instant cutoff);

    /** Used by the prune job; the cascade takes the certificates and contacts with it. */
    @Modifying
    @Query("delete from DirectoryServer s where s.lastSyncedAt < :cutoff")
    int deleteByLastSyncedAtBefore(@Param("cutoff") Instant cutoff);

    /**
     * The certificate roll-up across every entry, as one grouped query rather than a count
     * per state.
     */
    @Query("select s.certificateStatus as status, count(s) as total from DirectoryServer s "
            + "group by s.certificateStatus")
    List<CertificateStatusCount> countByCertificateStatus();

    /**
     * Removes one entry by name. The foreign keys cascade, so its certificates and its
     * identifiers or contacts go with it.
     */
    @Modifying
    @Query("delete from DirectoryServer s where s.dn = :dn")
    int deleteByDn(@Param("dn") String dn);
}

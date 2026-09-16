package com.winllc.certalert.repository;

import com.winllc.certalert.domain.DirectoryUser;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.datatables.repository.DataTablesRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * IC Persons, exposed to the search tables through {@link DataTablesRepository} so
 * DataTables paging, ordering, global search and per-column search map straight onto the
 * query.
 */
public interface DirectoryUserRepository extends DataTablesRepository<DirectoryUser, Long> {

    Optional<DirectoryUser> findByDn(String dn);

    /**
     * Loads a whole sync batch in one query. Certificates come along because reconciliation
     * always needs them; identifiers are left lazy and batch-fetched.
     */
    @EntityGraph(attributePaths = "certificates")
    List<DirectoryUser> findAllByDnIn(Collection<String> dns);

    @EntityGraph(attributePaths = "certificates")
    Optional<DirectoryUser> findWithCertificatesById(Long id);

    @Query("select i from DirectoryUser u join u.identifiers i where u.id = :id")
    Set<String> findIdentifiersById(@Param("id") Long id);

    long countByLastSyncedAtBefore(Instant cutoff);

    /** Used by the prune job; the cascade takes the certificates and identifiers with it. */
    @Modifying
    @Query("delete from DirectoryUser u where u.lastSyncedAt < :cutoff")
    int deleteByLastSyncedAtBefore(@Param("cutoff") Instant cutoff);
}

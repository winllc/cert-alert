package com.winllc.certalert.repository;

import com.winllc.certalert.domain.DirectoryUser;
import java.util.Optional;
import org.springframework.data.jpa.datatables.repository.DataTablesRepository;
import org.springframework.data.jpa.repository.EntityGraph;

/**
 * Users, exposed to the search tables through {@link DataTablesRepository} so DataTables
 * paging, ordering, global search and per-column search map straight onto the query.
 */
public interface DirectoryUserRepository extends DataTablesRepository<DirectoryUser, Long> {

    /** Used by the sync, which always goes on to reconcile the certificates. */
    @EntityGraph(attributePaths = "certificates")
    Optional<DirectoryUser> findByDn(String dn);

    @EntityGraph(attributePaths = "certificates")
    Optional<DirectoryUser> findWithCertificatesById(Long id);
}

package com.winllc.certalert.repository;

import com.winllc.certalert.domain.DirectoryServer;
import java.util.Optional;
import org.springframework.data.jpa.datatables.repository.DataTablesRepository;
import org.springframework.data.jpa.repository.EntityGraph;

/**
 * Servers, exposed to the search tables through {@link DataTablesRepository}.
 *
 * <p>The two collections are fetched by separate methods on purpose. Joining both in one
 * query multiplies the rows together, and a {@code List} of certificates would come back
 * holding duplicates.
 */
public interface DirectoryServerRepository extends DataTablesRepository<DirectoryServer, Long> {

    /** Used by the sync, which always goes on to reconcile the certificates. */
    @EntityGraph(attributePaths = "certificates")
    Optional<DirectoryServer> findByDn(String dn);

    @EntityGraph(attributePaths = "certificates")
    Optional<DirectoryServer> findWithCertificatesById(Long id);

    @EntityGraph(attributePaths = "serverPocs")
    Optional<DirectoryServer> findWithPocsById(Long id);
}

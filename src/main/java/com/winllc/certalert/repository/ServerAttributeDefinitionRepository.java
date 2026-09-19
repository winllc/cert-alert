package com.winllc.certalert.repository;

import com.winllc.certalert.domain.ServerAttributeDefinition;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServerAttributeDefinitionRepository extends JpaRepository<ServerAttributeDefinition, Long> {

    /** In the order somebody arranged them, then by name so ties are stable. */
    List<ServerAttributeDefinition> findAllByOrderByDisplayOrderAscNameAsc();

    Optional<ServerAttributeDefinition> findByNameIgnoreCase(String name);
}

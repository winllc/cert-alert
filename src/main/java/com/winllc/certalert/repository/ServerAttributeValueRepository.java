package com.winllc.certalert.repository;

import com.winllc.certalert.domain.ServerAttributeValue;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServerAttributeValueRepository extends JpaRepository<ServerAttributeValue, Long> {

    List<ServerAttributeValue> findByServerIdOrderByPositionAscIdAsc(Long serverId);

    List<ServerAttributeValue> findByServerIdAndDefinitionIdOrderByPositionAscIdAsc(Long serverId, Long definitionId);

    void deleteByServerIdAndDefinitionId(Long serverId, Long definitionId);

    /**
     * How many servers still hold each value of an attribute - what an option cannot be
     * taken away from underneath.
     */
    @Query("select v.value as value, count(v) as total from ServerAttributeValue v "
            + "where v.definition.id = :definitionId group by v.value")
    List<ValueUsage> countByValue(@Param("definitionId") Long definitionId);

    /** The most values any one server holds for this attribute. */
    @Query("select coalesce(max(size), 0) from (select count(v) as size from ServerAttributeValue v "
            + "where v.definition.id = :definitionId group by v.server.id)")
    long widestServer(@Param("definitionId") Long definitionId);

    long countByDefinitionId(Long definitionId);

    /** How many servers hold each attribute, for the list of definitions. */
    @Query("select v.definition.id as definitionId, count(distinct v.server.id) as total "
            + "from ServerAttributeValue v group by v.definition.id")
    List<DefinitionUsage> countServersByDefinition();

    /** Projection for {@link #countServersByDefinition}. */
    interface DefinitionUsage {
        Long getDefinitionId();

        long getTotal();
    }

    /** Projection for {@link #countByValue}. */
    interface ValueUsage {
        String getValue();

        long getTotal();
    }
}

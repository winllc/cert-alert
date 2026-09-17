package com.winllc.certalert.repository;

import com.winllc.certalert.domain.Project;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    Optional<Project> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);

    List<Project> findAllByOrderByNameAsc();

    /** With both sides attached, for the page about one project. */
    @EntityGraph(attributePaths = {"members", "servers"})
    Optional<Project> findWithMembersAndServersById(Long id);

    /** How many people and servers each project holds, for the list. */
    @Query("select p.id as projectId, size(p.members) as members, size(p.servers) as servers from Project p")
    List<ProjectSize> countMembership();

    /** The projects an entry belongs to, for its details page. */
    @Query("select p from Project p join p.members m where m.id = :userId order by p.name")
    List<Project> findByMemberId(@Param("userId") Long userId);

    @Query("select p from Project p join p.servers s where s.id = :serverId order by p.name")
    List<Project> findByServerId(@Param("serverId") Long serverId);

    /** Projection for {@link #countMembership()}. */
    interface ProjectSize {
        Long getProjectId();

        int getMembers();

        int getServers();
    }
}

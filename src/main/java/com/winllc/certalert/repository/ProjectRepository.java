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

    /** With every side attached, for the page about one project. */
    @EntityGraph(attributePaths = {"members", "admins", "servers"})
    Optional<Project> findWithMembersAndServersById(Long id);

    /** How many people and servers each project holds, for the list. */
    @Query("select p.id as projectId, size(p.members) as members, size(p.admins) as admins, "
            + "size(p.servers) as servers from Project p")
    List<ProjectSize> countMembership();

    /** The projects an entry belongs to, for its details page. */
    @Query("select p from Project p join p.members m where m.id = :userId order by p.name")
    List<Project> findByMemberId(@Param("userId") Long userId);

    @Query("select p from Project p join p.servers s where s.id = :serverId order by p.name")
    List<Project> findByServerId(@Param("serverId") Long serverId);

    /** The projects somebody runs, as distinct from the ones they are merely in. */
    @Query("select p from Project p join p.admins a where a.id = :userId order by p.name")
    List<Project> findByAdminId(@Param("userId") Long userId);

    /**
     * Whether this person runs a project this server belongs to - which is what lets them
     * manage that server's points of contact. See {@code ServerAccessPolicy}. Being in the
     * project is not enough: membership is a grouping, and running it is a role.
     */
    @Query("select count(p) > 0 from Project p join p.admins a join p.servers s "
            + "where a.id = :userId and s.id = :serverId")
    boolean existsAdminOfServer(@Param("userId") Long userId, @Param("serverId") Long serverId);

    /**
     * Everybody who runs a project this server belongs to. They hear about its certificates
     * expiring, which is the other half of what the role carries.
     */
    @Query("select distinct a.id from Project p join p.admins a join p.servers s where s.id = :serverId")
    List<Long> findAdminIdsByServerId(@Param("serverId") Long serverId);

    /** Projection for {@link #countMembership()}. */
    interface ProjectSize {
        Long getProjectId();

        int getMembers();

        int getAdmins();

        int getServers();
    }
}

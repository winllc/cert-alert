package com.winllc.certalert.repository;

import com.winllc.certalert.domain.ServerContact;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ServerContactRepository extends JpaRepository<ServerContact, Long> {

    /** The person is fetched with the row: every caller renders their name. */
    @EntityGraph(attributePaths = "user")
    List<ServerContact> findByServerIdOrderByAddedAtAscIdAsc(Long serverId);

    @EntityGraph(attributePaths = "user")
    Optional<ServerContact> findByIdAndServerId(Long id, Long serverId);

    boolean existsByServerIdAndUserId(Long serverId, Long userId);

    boolean existsByServerIdAndEmail(Long serverId, String email);

    /**
     * How many contacts each of these servers has, for the table. One query for a page of
     * rows, rather than a count subquery on every server the application ever selects -
     * which is the same query, charged to the hourly sweep as well.
     */
    @Query("select c.server.id as serverId, count(c) as total from ServerContact c "
            + "where c.server.id in :serverIds group by c.server.id")
    List<ServerContactCount> countByServerIdIn(@Param("serverIds") List<Long> serverIds);

    /**
     * Where to write about this server, read through the link so a person's current address
     * is used rather than the copy taken when they were added. Called only when an alert is
     * actually raised.
     */
    @Query("select coalesce(u.email, c.email) from ServerContact c left join c.user u "
            + "where c.server.id = :serverId and coalesce(u.email, c.email) is not null")
    List<String> findAddressesByServerId(@Param("serverId") Long serverId);

    static Map<Long, Integer> asMap(List<ServerContactCount> counts) {
        return counts.stream()
                .collect(java.util.stream.Collectors.toMap(
                        ServerContactCount::getServerId, count -> Math.toIntExact(count.getTotal())));
    }

    /** Projection for {@link #countByServerIdIn}. */
    interface ServerContactCount {
        Long getServerId();

        Long getTotal();
    }
}

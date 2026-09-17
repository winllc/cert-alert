package com.winllc.certalert.repository;

import com.winllc.certalert.domain.UserEmailAlias;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserEmailAliasRepository extends JpaRepository<UserEmailAlias, Long> {

    List<UserEmailAlias> findByUserIdOrderByAddressAsc(Long userId);

    Optional<UserEmailAlias> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndAddress(Long userId, String address);

    /** Just the addresses, for merging into the identifiers a serverPOC is matched against. */
    @Query("select a.address from UserEmailAlias a where a.user.id = :userId")
    List<String> findAddressesByUserId(@Param("userId") Long userId);

    /**
     * The addresses for a whole batch of people at once. The sweep needs them to rebuild
     * each person's identifier set, and one query for a batch of two hundred beats a lazy
     * collection loaded per person across a directory of a hundred thousand.
     */
    @Query("select a.user.id as userId, a.address as address from UserEmailAlias a where a.user.id in :userIds")
    List<AliasAddress> findAddressesByUserIdIn(@Param("userIds") Collection<Long> userIds);

    /** Everyone who answers to this address, which for a list is everyone on it. */
    @Query("select distinct a.user.id from UserEmailAlias a where a.address = :address")
    List<Long> findUserIdsByAddress(@Param("address") String address);

    /** Projection for {@link #findAddressesByUserIdIn}. */
    interface AliasAddress {
        Long getUserId();

        String getAddress();
    }
}

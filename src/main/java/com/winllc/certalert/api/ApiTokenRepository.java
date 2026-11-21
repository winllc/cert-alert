package com.winllc.certalert.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {

    Optional<ApiToken> findByTokenHash(String tokenHash);

    List<ApiToken> findAllByOrderByCreatedAtDesc();

    List<ApiToken> findByCreatorDnIgnoreCaseAndRevokedAtIsNull(String creatorDn);

    List<ApiToken> findByCreatedByAndRevokedAtIsNull(String createdBy);

    /** Records a use, in a transaction of its own. */
    @Transactional
    @Modifying
    @Query("update ApiToken t set t.lastUsedAt = :at, t.lastUsedFrom = :from where t.id = :id")
    int markUsed(@Param("id") Long id, @Param("at") Instant at, @Param("from") String from);
}

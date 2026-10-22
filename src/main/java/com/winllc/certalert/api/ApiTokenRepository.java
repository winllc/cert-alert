package com.winllc.certalert.api;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiTokenRepository extends JpaRepository<ApiToken, Long> {

    Optional<ApiToken> findByTokenHash(String tokenHash);

    List<ApiToken> findAllByOrderByCreatedAtDesc();

    List<ApiToken> findByCreatorDnIgnoreCaseAndRevokedAtIsNull(String creatorDn);

    List<ApiToken> findByCreatedByAndRevokedAtIsNull(String createdBy);
}

package com.winllc.certalert.repository;

import com.winllc.certalert.audit.AuditApiToken;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditApiTokenRepository extends JpaRepository<AuditApiToken, Long> {

    Optional<AuditApiToken> findByTokenHash(String tokenHash);

    List<AuditApiToken> findAllByOrderByCreatedAtDesc();
}

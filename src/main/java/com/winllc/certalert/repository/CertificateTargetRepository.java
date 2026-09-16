package com.winllc.certalert.repository;

import com.winllc.certalert.domain.CertificateTarget;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CertificateTargetRepository extends JpaRepository<CertificateTarget, Long> {

    List<CertificateTarget> findByEnabledTrue();

    Optional<CertificateTarget> findByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCase(String name);
}

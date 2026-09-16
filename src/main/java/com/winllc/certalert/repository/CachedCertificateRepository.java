package com.winllc.certalert.repository;

import com.winllc.certalert.domain.CachedCertificate;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CachedCertificateRepository extends JpaRepository<CachedCertificate, Long> {}

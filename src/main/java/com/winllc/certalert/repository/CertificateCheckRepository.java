package com.winllc.certalert.repository;

import com.winllc.certalert.domain.CertificateCheck;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CertificateCheckRepository extends JpaRepository<CertificateCheck, Long> {

    Page<CertificateCheck> findByTargetIdOrderByCheckedAtDesc(Long targetId, Pageable pageable);
}

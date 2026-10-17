package com.winllc.certalert.repository;

import com.winllc.certalert.audit.AuditDelivery;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditDeliveryRepository extends JpaRepository<AuditDelivery, String> {}

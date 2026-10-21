package com.winllc.certalert.repository;

import com.winllc.certalert.audit.AuditDelivery;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditDeliveryRepository extends JpaRepository<AuditDelivery, String> {

    /**
     * An output's cursor, locked for the rest of the transaction: two replicas moving it at
     * once take turns, and the cursor only ever moves forward (see AuditDelivery#delivered).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from AuditDelivery d where d.output = :output")
    Optional<AuditDelivery> lockByOutput(@Param("output") String output);
}

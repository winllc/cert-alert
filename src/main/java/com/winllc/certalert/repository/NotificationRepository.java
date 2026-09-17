package com.winllc.certalert.repository;

import com.winllc.certalert.domain.Notification;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipientUserIdOrderByCreatedAtDescIdDesc(Long recipientUserId, Pageable pageable);

    long countByRecipientUserIdAndReadAtIsNull(Long recipientUserId);

    long countByReadAtIsNull();

    long countByEmailedAtIsNotNull();

    Optional<Notification> findByIdAndRecipientUserId(Long id, Long recipientUserId);

    @Modifying
    @Query("update Notification n set n.readAt = :when where n.recipientUserId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("when") Instant when);

    /**
     * Whether this person has already been told about this certificate in this state, so a
     * sweep that runs every night does not tell them the same thing every night.
     */
    boolean existsByRecipientUserIdAndCertificateFingerprintAndSeverityAndKindAndCreatedAtAfter(
            Long recipientUserId,
            String certificateFingerprint,
            com.winllc.certalert.domain.Severity severity,
            com.winllc.certalert.domain.NotificationKind kind,
            Instant after);

    /** The same for a recipient who is only an address. */
    boolean existsByRecipientAddressAndCertificateFingerprintAndSeverityAndKindAndCreatedAtAfter(
            String recipientAddress,
            String certificateFingerprint,
            com.winllc.certalert.domain.Severity severity,
            com.winllc.certalert.domain.NotificationKind kind,
            Instant after);

    @Modifying
    @Query("delete from Notification n where n.createdAt < :cutoff and n.readAt is not null")
    int deleteReadBefore(@Param("cutoff") Instant cutoff);
}

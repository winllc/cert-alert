package com.winllc.certalert.repository;

import com.winllc.certalert.domain.Notification;
import java.time.Instant;
import java.util.Collection;
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

    /**
     * The same question asked of a whole credential rather than one certificate.
     *
     * <p>A person's signing and key encipherment certificates cross into a bad state within
     * moments of each other, because they were issued within moments of each other. Asked
     * one certificate at a time, that is two notifications saying the same thing; asked of
     * both fingerprints at once, it is one.
     */
    boolean existsByRecipientUserIdAndCertificateFingerprintInAndSeverityAndKindAndCreatedAtAfter(
            Long recipientUserId,
            java.util.Collection<String> certificateFingerprints,
            com.winllc.certalert.domain.Severity severity,
            com.winllc.certalert.domain.NotificationKind kind,
            Instant after);

    /** The same for a recipient who is only an address. */
    boolean existsByRecipientAddressAndCertificateFingerprintInAndSeverityAndKindAndCreatedAtAfter(
            String recipientAddress,
            java.util.Collection<String> certificateFingerprints,
            com.winllc.certalert.domain.Severity severity,
            com.winllc.certalert.domain.NotificationKind kind,
            Instant after);

    /**
     * The round-up a person already has, if any.
     *
     * <p>One per recipient rather than one per run: it reports what is expiring now, so a
     * second copy of it is not more news, it is the same news twice.
     */
    Optional<Notification> findFirstByRecipientUserIdAndKindOrderByCreatedAtDesc(
            Long recipientUserId, com.winllc.certalert.domain.NotificationKind kind);

    /** The same for a recipient who is only an address. */
    Optional<Notification> findFirstByRecipientAddressAndRecipientUserIdIsNullAndKindOrderByCreatedAtDesc(
            String recipientAddress, com.winllc.certalert.domain.NotificationKind kind);

    /**
     * Removes the round-ups this run did not write, which are the ones that no longer say
     * anything true: everything they named has been renewed, or the entry has gone.
     */
    @Modifying
    @Query("delete from Notification n where n.kind = :kind and n.id not in :keep")
    int deleteOfKindExcept(
            @Param("kind") com.winllc.certalert.domain.NotificationKind kind, @Param("keep") Collection<Long> keep);

    /** The same where this run wrote none at all, since {@code not in ()} is not valid. */
    @Modifying
    @Query("delete from Notification n where n.kind = :kind")
    int deleteOfKind(@Param("kind") com.winllc.certalert.domain.NotificationKind kind);

    @Modifying
    @Query("delete from Notification n where n.createdAt < :cutoff and n.readAt is not null")
    int deleteReadBefore(@Param("cutoff") Instant cutoff);
}

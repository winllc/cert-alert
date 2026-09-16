package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.CertificateTarget;
import com.winllc.certalert.domain.CheckStatus;
import java.time.Instant;

/** A monitored target, including a summary of its most recent check. */
public record CertificateTargetResponse(
        Long id,
        String name,
        String hostname,
        int port,
        String description,
        boolean enabled,
        CheckStatus lastStatus,
        Instant lastCheckedAt,
        Instant lastExpiresAt,
        Instant createdAt,
        Instant updatedAt) {

    public static CertificateTargetResponse from(CertificateTarget target) {
        return new CertificateTargetResponse(
                target.getId(),
                target.getName(),
                target.getHostname(),
                target.getPort(),
                target.getDescription(),
                target.isEnabled(),
                target.getLastStatus(),
                target.getLastCheckedAt(),
                target.getLastExpiresAt(),
                target.getCreatedAt(),
                target.getUpdatedAt());
    }
}

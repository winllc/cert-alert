package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.CertificateCheck;
import com.winllc.certalert.domain.CheckStatus;
import java.time.Instant;

/** The result of one certificate inspection. */
public record CertificateCheckResponse(
        Long id,
        CheckStatus status,
        Instant checkedAt,
        String subject,
        String issuer,
        String serialNumber,
        Instant notBefore,
        Instant notAfter,
        Long daysUntilExpiry,
        String errorMessage) {

    public static CertificateCheckResponse from(CertificateCheck check) {
        return new CertificateCheckResponse(
                check.getId(),
                check.getStatus(),
                check.getCheckedAt(),
                check.getSubject(),
                check.getIssuer(),
                check.getSerialNumber(),
                check.getNotBefore(),
                check.getNotAfter(),
                check.getDaysUntilExpiry(),
                check.getErrorMessage());
    }
}

package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.repository.CertificateStatusCount;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The certificate roll-up shown at the top of a search page.
 *
 * @param total every entry of this object type
 * @param byStatus how many hold certificates in each state, every state present
 */
public record DirectoryStats(long total, Map<CertificateStatus, Long> byStatus) {

    public static DirectoryStats from(List<CertificateStatusCount> counts) {
        Map<CertificateStatus, Long> byStatus = new EnumMap<>(CertificateStatus.class);
        // Fill every state, so the cards read zero rather than blank.
        for (CertificateStatus status : CertificateStatus.values()) {
            byStatus.put(status, 0L);
        }
        long total = 0;
        for (CertificateStatusCount count : counts) {
            byStatus.put(count.getStatus(), count.getTotal());
            total += count.getTotal();
        }
        return new DirectoryStats(total, byStatus);
    }
}

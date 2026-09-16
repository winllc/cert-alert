package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryServer;
import java.time.Instant;

/** One row of the servers table. Field names mirror the entity's property names. */
public record DirectoryServerRow(
        Long id,
        String dn,
        String commonName,
        String fqdn,
        String description,
        String operatingSystem,
        String serialNumber,
        String serverPocDisplay,
        String organization,
        String organizationalUnit,
        int certificateCount,
        CertificateStatus certificateStatus,
        Instant earliestExpiry,
        Instant latestExpiry,
        Instant lastSyncedAt) {

    public static DirectoryServerRow from(DirectoryServer server) {
        return new DirectoryServerRow(
                server.getId(),
                server.getDn(),
                server.getCommonName(),
                server.getFqdn(),
                server.getDescription(),
                server.getOperatingSystem(),
                server.getSerialNumber(),
                server.getServerPocDisplay(),
                server.getOrganization(),
                server.getOrganizationalUnit(),
                server.getCertificateCount(),
                server.getCertificateStatus(),
                server.getEarliestExpiry(),
                server.getLatestExpiry(),
                server.getLastSyncedAt());
    }
}

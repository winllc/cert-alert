package com.winllc.certalert.web.dto;

import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryServer;
import java.time.Instant;

/**
 * One row of the servers table. Field names mirror the entity's property names.
 *
 * @param serverPocDisplay what the directory publishes, flattened
 * @param managedContactCount how many contacts were added here on top of that
 */
public record DirectoryServerRow(
        Long id,
        String dn,
        String commonName,
        String uid,
        String givenName,
        String description,
        String serverUrl,
        String icServerAddress,
        String atoStatus,
        String lifeCycleStatus,
        String employeeType,
        String countryOfAffiliation,
        String dutyOrganization,
        String adminOrganization,
        Boolean icMember,
        String icNetworks,
        String serverPocDisplay,
        int managedContactCount,
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
                server.getUid(),
                server.getGivenName(),
                server.getDescription(),
                server.getServerUrl(),
                server.getIcServerAddress(),
                server.getAtoStatus(),
                server.getLifeCycleStatus(),
                server.getEmployeeType(),
                server.getCountryOfAffiliation(),
                server.getDutyOrganization(),
                server.getAdminOrganization(),
                server.getIcMember(),
                server.getIcNetworks(),
                server.getServerPocDisplay(),
                // Filled in per page of rows; see DirectoryDataTablesController.
                0,
                server.getOrganization(),
                server.getOrganizationalUnit(),
                server.getCertificateCount(),
                server.getCertificateStatus(),
                server.getEarliestExpiry(),
                server.getLatestExpiry(),
                server.getLastSyncedAt());
    }

    /** The count is not on the entity, so it arrives after the row is built. */
    public DirectoryServerRow withManagedContactCount(int count) {
        return new DirectoryServerRow(
                id,
                dn,
                commonName,
                uid,
                givenName,
                description,
                serverUrl,
                icServerAddress,
                atoStatus,
                lifeCycleStatus,
                employeeType,
                countryOfAffiliation,
                dutyOrganization,
                adminOrganization,
                icMember,
                icNetworks,
                serverPocDisplay,
                count,
                organization,
                organizationalUnit,
                certificateCount,
                certificateStatus,
                earliestExpiry,
                latestExpiry,
                lastSyncedAt);
    }
}
